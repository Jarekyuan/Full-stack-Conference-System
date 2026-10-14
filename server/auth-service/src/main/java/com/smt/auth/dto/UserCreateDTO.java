package com.smt.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建用户入参（ADMIN）
 */
@Data
public class UserCreateDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(max = 50, message = "用户名最长 50 字符")
    private String username;

    @NotBlank(message = "初始密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度 6-32 位")
    private String password;

    @NotBlank(message = "姓名不能为空")
    @Size(max = 50, message = "姓名最长 50 字符")
    private String realName;

    @Email(message = "邮箱格式不正确")
    private String email;

    /** 角色：USER / APPROVER / ADMIN，缺省 USER */
    @Pattern(regexp = "USER|APPROVER|ADMIN", message = "角色只能是 USER/APPROVER/ADMIN")
    private String role;
}
