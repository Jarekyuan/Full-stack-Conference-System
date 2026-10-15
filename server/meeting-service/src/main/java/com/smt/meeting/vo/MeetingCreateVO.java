package com.smt.meeting.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 创建预约响应（对照 docs/04 4.4）
 */
@Data
@AllArgsConstructor
public class MeetingCreateVO {

    private Long meetingId;
    /** 落库状态：10 待审批 / 20 已预约 */
    private Integer status;
    private boolean needApproval;
    /** 面向用户的提示（需审批："预约已提交，等待审批"；免审："预约成功"） */
    private String msg;
}
