package com.smt.auth.service;

import cn.hutool.core.util.StrUtil;
import com.smt.auth.dto.UserCreateDTO;
import com.smt.auth.entity.SysUser;
import com.smt.auth.mapper.SysRoleMapper;
import com.smt.auth.mapper.SysUserMapper;
import com.smt.auth.vo.UserBriefVO;
import com.smt.auth.vo.UserRoleItem;
import com.smt.auth.vo.UserVO;
import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.common.result.PageResult;
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
    private final PasswordEncoder passwordEncoder;

    /** 页码上限：防 offset 乘法溢出（上限 × 最大 size 仍远小于 Integer.MAX_VALUE），也顺带挡住深分页扫描 */
    private static final int MAX_PAGE = 100_000;

    /** 选参会人搜索：空关键字直接返回空（避免无意义全表扫描）；搜索场景不装配角色 */
    public List<UserVO> search(String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return List.of();
        }
        return sysUserMapper.searchByKeyword(keyword.trim()).stream()
                .map(UserService::toBasicVO)
                .toList();
    }

    /** 批量查询用户精简信息（服务间内部接口：id → {id, realName, email}） */
    public List<UserBriefVO> batchByIds(List<Long> ids) {
        return sysUserMapper.selectByIds(ids).stream()
                .map(user -> new UserBriefVO(user.getId(), user.getRealName(), user.getEmail()))
                .toList();
    }

    /** 分页（可按部门筛选；角色一次批量装配，避免逐行 N+1） */
    public PageResult<UserVO> page(int page, int size, Long deptId) {
        // page 双向钳制：只设下限时大 page 会让 offset 溢出成负数 → LIMIT 报错 → 500（评审 R1）
        int safePage = Math.min(Math.max(page, 1), MAX_PAGE);
        int safeSize = Math.min(Math.max(size, 1), 100);
        long total = sysUserMapper.countAll(deptId);
        List<UserVO> list = sysUserMapper.selectPage(deptId, (safePage - 1) * safeSize, safeSize);
        fillRoles(list);
        return PageResult.of(list, total, safePage, safeSize);
    }

    /** 创建用户 + 绑定角色（同一事务：用户与角色关联要么都成功、要么都回滚） */
    @Transactional
    public Long create(UserCreateDTO dto) {
        // 友好校验（并发窗口由 uk_username 唯一索引兜底）
        if (sysUserMapper.selectByUsername(dto.getUsername()) != null) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS);
        }
        SysUser user = new SysUser();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setRealName(dto.getRealName());
        user.setEmail(dto.getEmail());
        user.setDeptId(dto.getDeptId());
        user.setStatus(1);
        sysUserMapper.insert(user);

        sysRoleMapper.batchInsertUserRoles(user.getId(), dto.getRoleIds());
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

    /** 基础字段转换（不含角色装配，供搜索场景用） */
    private static UserVO toBasicVO(SysUser user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setEmail(user.getEmail());
        vo.setAvatar(user.getAvatar());
        vo.setDeptId(user.getDeptId());
        vo.setStatus(user.getStatus());
        return vo;
    }
}
