package com.smt.auth.vo;

import lombok.Data;

/**
 * 用户出参 VO（绝不包含 password）
 */
@Data
public class UserVO {

    private Long id;
    private String username;
    private String realName;
    private String email;
    private String avatar;
    private String role;
    private Integer status;
}
