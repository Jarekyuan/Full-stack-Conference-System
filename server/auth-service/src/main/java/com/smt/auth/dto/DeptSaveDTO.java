package com.smt.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 部门新增/修改入参（ADMIN）
 */
@Data
public class DeptSaveDTO {

    @NotBlank(message = "部门名称不能为空")
    @Size(max = 50, message = "部门名称最长 50 字符")
    private String name;

    /** 父部门ID（0 = 根；创建时有效，修改时忽略） */
    private Long parentId;

    private Integer sortOrder;

    /** 状态（修改时可选：0 停用 / 1 启用） */
    private Integer status;
}
