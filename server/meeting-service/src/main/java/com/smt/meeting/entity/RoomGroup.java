package com.smt.meeting.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会议室分组（对应表 room_group，字段见 docs/03 数据库设计）
 */
@Data
public class RoomGroup {

    private Long id;
    private String name;
    /** 排序值（越小越靠前） */
    private Integer sortOrder;
    private LocalDateTime createdAt;
}
