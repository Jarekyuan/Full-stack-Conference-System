package com.smt.auth.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体（对应表 sys_user，字段见 docs/03 数据库设计）
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
    /** USER / APPROVER / ADMIN */
    private String role;
    /** 0 禁用 / 1 启用 */
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
