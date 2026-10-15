package com.smt.auth.vo;

import lombok.Data;

import java.util.List;

/**
 * 登录响应（docs/04 接口设计 4.1）
 */
@Data
public class LoginResponse {

    private String accessToken;
    private String refreshToken;
    /** accessToken 有效期（秒） */
    private Long expiresIn;
    private User user;

    @Data
    public static class User {
        private Long id;
        private String username;
        private String realName;
        /** 角色 code 列表（RBAC 多角色，如 ["ADMIN","APPROVER"]） */
        private List<String> roles;
        /** 所属部门（可空 = 未分配） */
        private Long deptId;
        private String deptName;
        private String avatar;
    }
}
