package com.smt.auth.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体（对应表 sys_user，字段见 docs/03 数据库设计）
 * 角色不再在此实体上（RBAC 多角色经 sys_user_role 关联，查询时另行装配）
 */
@Data
public class SysUser {

    private Long id;
    private String username;
    /** BCrypt 密码哈希 */
    private String password;
    private String realName;
    private String email;
    private String avatar;
    /** 所属部门（sys_dept.id，可空 = 未分配） */
    private Long deptId;
    /** 0 禁用 / 1 启用 */
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
