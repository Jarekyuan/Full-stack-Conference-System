package com.smt.auth.service;

import cn.hutool.core.util.StrUtil;
import com.smt.auth.dto.UserCreateDTO;
import com.smt.auth.entity.SysRole;
import com.smt.auth.entity.SysUser;
import com.smt.auth.mapper.SysDeptMapper;
import com.smt.auth.mapper.SysRoleMapper;
import com.smt.auth.mapper.SysUserMapper;
import com.smt.auth.vo.UserBriefVO;
import com.smt.auth.vo.UserRoleItem;
import com.smt.auth.vo.UserSearchVO;
import com.smt.auth.vo.UserVO;
import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.common.result.PageResult;
import com.smt.common.util.PageBounds;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
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

    /** 分页（可按部门筛选；角色一次批量装配，避免逐行 N+1） */
    public PageResult<UserVO> page(int page, int size, Long deptId) {
        // 分页边界统一由 PageBounds 钳制（page 溢出会让 offset 变负数 → LIMIT 报错 → 500，评审 R1）
        PageBounds bounds = PageBounds.of(page, size);
        long total = sysUserMapper.countAll(deptId);
        List<UserVO> list = sysUserMapper.selectPage(deptId, bounds.offset(), bounds.size());
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

    public void updateStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "status 只能为 0（停用）或 1（启用）");
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
