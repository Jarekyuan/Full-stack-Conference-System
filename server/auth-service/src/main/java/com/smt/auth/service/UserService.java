package com.smt.auth.service;

import cn.hutool.core.util.StrUtil;
import com.smt.auth.dto.UserCreateDTO;
import com.smt.auth.dto.UserUpdateDTO;
import com.smt.auth.entity.SysDept;
import com.smt.auth.entity.SysRole;
import com.smt.auth.entity.SysUser;
import com.smt.auth.mapper.SysDeptMapper;
import com.smt.auth.mapper.SysRoleMapper;
import com.smt.auth.mapper.SysUserMapper;
import com.smt.auth.vo.UserBriefVO;
import com.smt.auth.vo.UserRoleItem;
import com.smt.auth.vo.UserSearchVO;
import com.smt.auth.vo.UserVO;
import com.smt.common.context.UserContext;
import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.common.result.PageResult;
import com.smt.common.util.PageBounds;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户服务：搜索 / 分页 / 创建 / 启用停用（T1.12；v1.3 起角色走 RBAC 多角色 + 部门）
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysDeptMapper sysDeptMapper;
    private final PasswordEncoder passwordEncoder;

    /** 选参会人搜索：空关键字直接返回空（避免无意义全表扫描）；出参为场景专用的 UserSearchVO（不装配角色、不含 email） */
    public List<UserSearchVO> search(String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return List.of();
        }
        return sysUserMapper.searchBrief(keyword.trim());
    }

    /** 批量查询用户精简信息（服务间内部接口：id → {id, realName, email}） */
    public List<UserBriefVO> batchByIds(List<Long> ids) {
        return sysUserMapper.selectByIds(ids).stream()
                .map(user -> new UserBriefVO(user.getId(), user.getRealName(), user.getEmail()))
                .toList();
    }

    /**
     * 分页（可按部门/整棵子树筛选；角色一次批量装配，避免逐行 N+1）。
     *
     * <p>{@code includeChildren=true} 时把子树展开成 id 列表再查——部门量小（单页树），
     * 比在 SQL 里写递归 CTE 更直观，也避免 MySQL 递归深度/写法差异。
     */
    public PageResult<UserVO> page(int page, int size, Long deptId, boolean includeChildren) {
        // 分页边界统一由 PageBounds 钳制（page 溢出会让 offset 变负数 → LIMIT 报错 → 500，评审 R1）
        PageBounds bounds = PageBounds.of(page, size);
        List<Long> deptIds = resolveDeptIds(deptId, includeChildren);
        long total = sysUserMapper.countAll(deptIds);
        List<UserVO> list = sysUserMapper.selectPage(deptIds, bounds.offset(), bounds.size());
        fillRoles(list);
        return PageResult.of(list, total, bounds.page(), bounds.size());
    }

    /** 创建用户 + 绑定角色（同一事务：用户与角色关联要么都成功、要么都回滚） */
    @Transactional
    public Long create(UserCreateDTO dto) {
        // 友好校验（并发窗口由 uk_username 唯一索引兜底）
        if (sysUserMapper.selectByUsername(dto.getUsername()) != null) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS);
        }
        ensureDeptExists(dto.getDeptId());
        List<Long> roleIds = ensureRolesExist(dto.getRoleIds());

        SysUser user = new SysUser();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setRealName(dto.getRealName());
        user.setEmail(dto.getEmail());
        user.setDeptId(dto.getDeptId());
        user.setStatus(1);
        sysUserMapper.insert(user);

        sysRoleMapper.batchInsertUserRoles(user.getId(), roleIds);
        return user.getId();
    }

    /**
     * 编辑用户（T1.20）：改姓名/邮箱/部门/角色。
     * 用户名与密码不在本接口修改（理由见 {@link UserUpdateDTO} 的类注释）。
     */
    @Transactional
    public void update(Long id, UserUpdateDTO dto) {
        if (sysUserMapper.selectById(id) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "用户不存在");
        }
        ensureDeptExists(dto.getDeptId());
        List<Long> roleIds = ensureRolesExist(dto.getRoleIds());
        ensureSelfKeepsAdmin(id, roleIds);

        SysUser user = new SysUser();
        user.setId(id);
        user.setRealName(dto.getRealName());
        user.setEmail(dto.getEmail());
        user.setDeptId(dto.getDeptId());
        sysUserMapper.updateProfile(user);

        // 角色是"全量替换"语义：先清空再批量重建（同一事务，失败一起回滚）
        sysRoleMapper.deleteUserRoles(id);
        sysRoleMapper.batchInsertUserRoles(id, roleIds);
    }

    public void updateStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "status 只能为 0（停用）或 1（启用）");
        }
        // 防止唯一管理员把自己停用后锁死（与"移除自己的 ADMIN 角色"同一类保护）
        if (status == 0 && id.equals(UserContext.getUserId())) {
            throw new BusinessException(ErrorCode.SELF_OPERATION_FORBIDDEN);
        }
        if (sysUserMapper.updateStatus(id, status) == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "用户不存在");
        }
    }

    /** 批量装配角色 code（一次查询：userIds IN (...) → group by userId） */
    private void fillRoles(List<UserVO> list) {
        if (list.isEmpty()) {
            return;
        }
        List<Long> userIds = list.stream().map(UserVO::getId).toList();
        Map<Long, List<String>> rolesByUser = sysRoleMapper.selectCodesByUserIds(userIds).stream()
                .collect(Collectors.groupingBy(UserRoleItem::getUserId,
                        Collectors.mapping(UserRoleItem::getCode, Collectors.toList())));
        list.forEach(vo -> vo.setRoles(rolesByUser.getOrDefault(vo.getId(), List.of())));
    }

    /**
     * 把 deptId + includeChildren 解析成 SQL 的 IN 列表。
     * null = 不按部门过滤（全部用户，含未分配部门的用户）。
     * BFS 展开 + visited：脏数据成环时不死循环（树正常时 visited 只起防护作用）。
     */
    private List<Long> resolveDeptIds(Long deptId, boolean includeChildren) {
        if (deptId == null) {
            return null;
        }
        if (!includeChildren) {
            return List.of(deptId);
        }
        Map<Long, List<Long>> childrenByParent = sysDeptMapper.selectAll().stream()
                .collect(Collectors.groupingBy(SysDept::getParentId,
                        Collectors.mapping(SysDept::getId, Collectors.toList())));
        List<Long> result = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(deptId);
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            result.add(current);
            queue.addAll(childrenByParent.getOrDefault(current, List.of()));
        }
        return result;
    }

    /**
     * 不能移除自己的 ADMIN 角色：唯一管理员把自己降权后，管理端（含本页面）再也进不去，
     * 而且没有任何界面入口能恢复——这类自锁只能在数据库里手工救。
     */
    private void ensureSelfKeepsAdmin(Long id, List<Long> roleIds) {
        if (!id.equals(UserContext.getUserId())) {
            return;
        }
        boolean keepsAdmin = sysRoleMapper.selectByIds(roleIds).stream()
                .anyMatch(role -> "ADMIN".equals(role.getCode()));
        if (!keepsAdmin) {
            throw new BusinessException(ErrorCode.SELF_OPERATION_FORBIDDEN);
        }
    }

    /**
     * 部门存在性校验（评审 R4）：无外键，悬空 deptId 会创建一个"查不到部门"的用户。
     */
    private void ensureDeptExists(Long deptId) {
        if (deptId != null && sysDeptMapper.selectById(deptId) == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "部门不存在: " + deptId);
        }
    }

    /**
     * 角色存在性校验（评审 R4）：悬空 roleId 最坏的情况是**用户能建出来但永远登不进任何功能**——
     * roles 为空 → 所有 @PreAuthorize 都过不去，而管理员在界面上看不出根因。
     * 返回去重后的 id 列表（同一角色重复提交会撞 uk_user_role 唯一键）。
     */
    private List<Long> ensureRolesExist(List<Long> roleIds) {
        List<Long> distinctIds = roleIds.stream().distinct().toList();
        List<Long> foundIds = sysRoleMapper.selectByIds(distinctIds).stream()
                .map(SysRole::getId)
                .toList();
        List<Long> missing = distinctIds.stream().filter(id -> !foundIds.contains(id)).toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "角色不存在: " + missing);
        }
        return distinctIds;
    }

}
