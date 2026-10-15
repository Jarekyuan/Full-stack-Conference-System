package com.smt.meeting.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 会议列表 Mapper 查询参数（Service 层组装：把 Web 层 DTO 解析为类型化参数）
 * scope=mine 时按"我组织的 或 我参与的"过滤（userId 必填）
 */
@Data
public class MeetingQueryParam {

    /** mine / all */
    private String scope;
    /** scope=mine 时的当前用户 */
    private Long userId;
    /** 状态多值（WHERE status IN (...)） */
    private List<Integer> statuses;
    private Long roomId;
    private LocalDate startDate;
    private LocalDate endDate;
    private String keyword;
    private int offset;
    private int size;
}
