package com.smt.notification.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量删除通知入参（T6.2）。
 * 上限 100 与列表分页上限一致：一次删太多没有意义，也会把单条 SQL 撑得过大。
 */
@Data
public class NotificationDeleteDTO {

    @NotEmpty(message = "请选择要删除的通知")
    @Size(max = 100, message = "一次最多删除 100 条")
    private List<Long> ids;
}
