package com.smt.meeting.vo;

/**
 * 审批动作结果（docs/04 4.7：`{ "meetingId": 1002, "status": 20 }`）
 *
 * 回带 status 是为了让前端**不用猜**审批后会议变成什么状态（通过 → 20 已预约，拒绝 → 60 已拒绝），
 * 直接用它刷新本地视图。
 */
public record ApprovalResultVO(Long meetingId, Integer status) {
}
