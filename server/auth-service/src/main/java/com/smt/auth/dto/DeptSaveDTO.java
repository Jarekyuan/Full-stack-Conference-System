package com.smt.auth.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

    @Min(value = 0, message = "排序不能小于 0")
    @Max(value = 9999, message = "排序最大 9999")
    private Integer sortOrder;

    /** 状态（修改时可选：0 停用 / 1 启用） */
    @Min(value = 0, message = "状态只能是 0 或 1")
    @Max(value = 1, message = "状态只能是 0 或 1")
    private Integer status;
}
