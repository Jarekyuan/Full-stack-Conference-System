package com.smt.auth.controller;

import com.smt.auth.mapper.SysRoleMapper;
import com.smt.auth.vo.RoleVO;
import com.smt.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色接口（对外路径 /api/roles）：角色列表（用户管理分配角色用）
 * 只读字典查询，直接走 Mapper（无业务逻辑，无需 Service 层）
 */
@RestController
@RequestMapping("/roles")
@RequiredArgsConstructor
public class RoleController {

    private final SysRoleMapper sysRoleMapper;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<RoleVO>> list() {
        return Result.success(sysRoleMapper.selectAllEnabled().stream()
                .map(role -> new RoleVO(role.getId(), role.getCode(), role.getName()))
                .toList());
    }
}
