package com.smt.meeting.vo;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 周期会议的冲突条目（T5.1）：比单场冲突多一个 {@code occurrenceStart}（**哪一场**撞了）。
 *
 * <p>为什么必须带场次：一次提交 5 场、其中第 3 场有冲突，只回"有冲突"用户没法改——
 * 得让他知道是"10-26 那一场"，才能决定是挪整系列还是单独处理。
 * 场的判定方式：冲突会议的 start_time 落在某场次的时间窗内（同一天的占用才算这一场的冲突）。
 */
@Data
public class RecurringConflictVO {

    /** 冲突所属的场次开始时间（展开出来的那一场） */
    private LocalDateTime occurrenceStart;
    private Long meetingId;
    private String title;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long organiserId;
    private String organiserName;
}
