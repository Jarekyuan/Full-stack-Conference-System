package com.smt.auth.vo;

import lombok.Data;

/**
 * 用户-角色 code 的批量查询行（分页列表装配角色用，避免 N+1）
 */
@Data
public class UserRoleItem {

    private Long userId;
    private String code;
}
