package com.smt.auth.vo;

/**
 * 角色出参（docs/04：GET /api/roles，用户管理分配角色用）
 */
public record RoleVO(Long id, String code, String name) {
}
