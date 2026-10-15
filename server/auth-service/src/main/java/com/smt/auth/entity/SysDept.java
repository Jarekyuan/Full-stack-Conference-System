package com.smt.auth.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 部门实体（对应表 sys_dept，树形结构）
 * 用途仅"组织结构"：组织与筛选用户，不参与权限判定
 */
@Data
public class SysDept {

    private Long id;
    /** 父部门ID（0 = 根） */
    private Long parentId;
    private String name;
    private Integer sortOrder;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
