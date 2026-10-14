package com.smt.meeting.vo;

import lombok.Data;

import java.util.List;

/**
 * 会议室出参 VO（列表与详情共用：含分组名 + 策略字段，联查一次直出）
 */
@Data
public class RoomVO {

    private Long id;
    private String name;
    private Long groupId;
    private String groupName;
    private Integer capacity;
    private String location;
    private List<String> images;
    private List<String> deviceTags;
    /** 0 可用 / 1 维护中 */
    private Integer status;

    // ---- 预约策略（列表作摘要展示，详情全量） ----
    private Integer needApproval;
    private Integer advanceDays;
    private Integer maxDurationMinutes;
    private Integer cancelDeadlineMinutes;
    private Integer bufferMinutes;
    private Integer allowRecurring;
}
