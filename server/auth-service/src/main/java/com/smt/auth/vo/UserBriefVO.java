package com.smt.auth.vo;

/**
 * 用户精简信息（服务间内部接口出参：不含 password 等敏感字段）
 */
public record UserBriefVO(Long id, String realName, String email) {
}
