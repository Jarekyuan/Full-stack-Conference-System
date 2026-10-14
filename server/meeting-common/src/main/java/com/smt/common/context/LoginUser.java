package com.smt.common.context;

/**
 * 当前登录用户（由网关校验 JWT 后经请求头注入，见 docs/04 接口设计 1.4）
 */
public record LoginUser(Long userId, String role) {
}
