package com.smt.meeting.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 会议列表查询参数（对照 docs/04 4.5）
 * status 支持逗号分隔多值（如 "10,20"），解析为 List<Integer> 供 <foreach> 使用。
 */
@Data
public class MeetingQueryDTO {

    /** mine（我组织/参与的，默认）/ all（ADMIN/APPROVER 可查全部） */
    private String scope = "mine";

    /** 状态多值：逗号分隔，如 "10,20" */
    private String status;

    private Long roomId;

    /** 按会议开始时间的日期范围（含端点的自然日） */
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    /** 主题模糊搜索 */
    private String keyword;

    private Integer page = 1;
    private Integer size = 10;

    /** "10,20" → [10, 20]（空/非法项忽略） */
    public List<Integer> parseStatuses() {
        List<Integer> result = new ArrayList<>();
        if (status == null || status.isBlank()) {
            return result;
        }
        for (String part : status.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                result.add(Integer.valueOf(trimmed));
            } catch (NumberFormatException ignored) {
                // 非法状态值忽略（宽松解析，避免因单个错误值整单失败）
            }
        }
        return result;
    }
}
