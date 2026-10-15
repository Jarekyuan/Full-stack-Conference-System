package com.smt.auth.vo;

import lombok.Data;

/**
 * 选参会人搜索结果（`GET /users/search` 专用出参，契约见 docs/04 3.1）。
 *
 * <p>为什么不复用 {@link UserVO}（评审 P1-13）：搜索路径**不装配角色**，分页路径装配，
 * 同一个 VO 于是同时以 `roles: null` 和 `roles: [...]` 两种形状出现在同名前缀的接口上；
 * 前端按 `roles: string[]` 消费就会踩空。按场景裁剪字段是本项目一贯约定（同 4.5 的列表/详情）。
 * 顺带不返回 email —— 该接口对任意登录用户开放（与评审 R8 同因）。
 *
 * <p>用 @Data 类而非 record：本项目的 VO 一律走 MyBatis `resultType` + 列别名（见 UserRoleItem），
 * 无参构造 + setter 是这条链路上最稳的形态。
 */
@Data
public class UserSearchVO {

    private Long id;
    private String realName;
    /** 所属部门名（LEFT JOIN 直出；未分配部门时为 null） */
    private String deptName;
}
