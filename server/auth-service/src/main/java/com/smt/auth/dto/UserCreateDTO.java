package com.smt.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建用户入参（ADMIN）
 * v1.3：角色改为角色 ID 列表（RBAC 多角色），新增所属部门
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

    /** 角色 ID 列表（sys_role.id，至少一个；如 [1] = USER） */
    @NotEmpty(message = "至少分配一个角色")
    private List<Long> roleIds;

    /** 所属部门（sys_dept.id，可空 = 未分配） */
    private Long deptId;
}
