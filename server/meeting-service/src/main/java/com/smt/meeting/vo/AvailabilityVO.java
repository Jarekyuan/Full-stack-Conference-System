package com.smt.meeting.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 批量可用性查询响应（结构对照 docs/04 4.3：data.items[]）
 */
@Data
@AllArgsConstructor
public class AvailabilityVO {

    private List<Item> items;

    @Data
    @AllArgsConstructor
    public static class Item {
        private Long roomId;
        /** 该时段是否可用（conflicts 为空即可用） */
        private boolean available;
        /** 冲突会议简报（可用时为空数组） */
        private List<MeetingConflictVO> conflicts;
    }
}
