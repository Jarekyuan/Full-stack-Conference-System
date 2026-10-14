package com.smt.auth.vo;

import lombok.Data;

/**
 * 登录响应（docs/04 接口设计 4.1）
 * refreshToken / expiresIn 在 T1.11 双 token 完整版中填充
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
        private String role;
        private String avatar;
    }
}
