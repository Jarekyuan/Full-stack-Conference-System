package com.smt.auth.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色实体（对应表 sys_role，RBAC 五表之一）
 */
@Data
public class SysRole {

    private Long id;
    /** 角色编码：USER / APPROVER / ADMIN（鉴权按 code） */
    private String code;
    private String name;
    private String description;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
