package com.smt.meeting.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会议列表项 VO（对照 docs/04 4.5）
 * 设计说明 ★：返回 roomName/organiserName 冗余展示字段（避免前端 N+1），
 * 但不含 remark 与参会人明细（详情接口才给）——接口按场景裁剪字段。
 */
@Data
public class MeetingVO {

    private Long id;
    private String title;
    private Long roomId;
    private String roomName;
    private Long organiserId;
    private String organiserName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /** 10 待审批 / 20 已预约 / 30 进行中 / 40 已结束 / 50 已取消 / 60 已拒绝 */
    private Integer status;
    /** 会议室策略：是否需审批（前端展示"需审批"标记） */
    private Integer needApproval;
    /** 参会人数（子查询统计） */
    private Integer participantCount;
    private LocalDateTime createdAt;
}
