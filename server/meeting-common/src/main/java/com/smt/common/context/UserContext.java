package com.smt.common.context;

import java.util.List;

/**
 * 登录用户上下文（ThreadLocal）
 * 由 UserHeaderFilter 写入、请求结束时清理——防止线程池复用导致数据串号
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser loginUser) {
        HOLDER.set(loginUser);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    public static Long getUserId() {
        LoginUser loginUser = HOLDER.get();
        return loginUser == null ? null : loginUser.userId();
    }

    /** 角色 code 列表（无身份时返回空列表） */
    public static List<String> getRoles() {
        LoginUser loginUser = HOLDER.get();
        return loginUser == null ? List.of() : loginUser.roles();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
