package com.smt.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 编辑用户入参（ADMIN，T1.20）。
 *
 * <p>与 {@link UserCreateDTO} 的差异是刻意的：
 * <ul>
 *   <li><b>没有 username</b>：登录名不可变——改登录名等于换身份，历史审计/会议归属都会对不上；</li>
 *   <li><b>没有 password</b>：密码重置是另一条路径（本轮不做），不能借"编辑资料"顺手改掉；</li>
 *   <li>部门/角色可改：这正是"把用户绑定到部门树"的写入口。</li>
 * </ul>
 */
@Data
public class UserUpdateDTO {

    @NotBlank(message = "姓名不能为空")
    @Size(max = 50, message = "姓名最长 50 字符")
    private String realName;

    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱最长 100 字符")
    private String email;

    /** 角色 ID 列表（sys_role.id，至少一个） */
    @NotEmpty(message = "至少分配一个角色")
    private List<Long> roleIds;

    /** 所属部门（sys_dept.id，可空 = 未分配） */
    private Long deptId;
}
