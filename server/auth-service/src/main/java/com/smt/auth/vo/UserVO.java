package com.smt.auth.vo;

import lombok.Data;

import java.util.List;

/**
 * 用户出参 VO（绝不包含 password）
 */
@Data
public class UserVO {

    private Long id;
    private String username;
    private String realName;
    private String email;
    private String avatar;
    /** 角色 code 列表（RBAC 多角色） */
    private List<String> roles;
    /** 所属部门（可空 = 未分配） */
    private Long deptId;
    private String deptName;
    private Integer status;
}
