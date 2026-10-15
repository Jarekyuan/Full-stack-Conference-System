package com.smt.common.context;

import java.util.List;

/**
 * 当前登录用户（由网关校验 JWT 后经请求头注入，见 docs/04 接口设计 1.4）
 * roles：角色 code 列表（RBAC 多角色，v1.3 起；鉴权语义 = 任一角色满足即通过）
 */
public record LoginUser(Long userId, List<String> roles) {
}
