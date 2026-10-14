package com.smt.meeting.entity;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会议室（对应表 meeting_room，字段见 docs/03 数据库设计）
 * images / deviceTags 为 JSON 列，由 JsonTypeHandler 在 XML resultMap 中处理
 */
@Data
public class MeetingRoom {

    private Long id;
    private String name;
    private Long groupId;
    private Integer capacity;
    private String location;
    /** 图片 URL 数组（COS） */
    private List<String> images;
    /** 设备标签，如 ["投影仪","视频会议"] */
    private List<String> deviceTags;
    /** 0 可用 / 1 维护中 */
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
