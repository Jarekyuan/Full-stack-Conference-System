package com.smt.auth.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量查询用户入参（服务间内部接口）
 */
@Data
public class UserBatchDTO {

    @NotEmpty(message = "ids 不能为空")
    @Size(max = 100, message = "单次最多查询 100 个 id")
    private List<Long> ids;
}
