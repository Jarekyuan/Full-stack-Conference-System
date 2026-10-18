package com.smt.meeting.vo;

/**
 * 可延长上限（`GET /meetings/{id}/extend-limit`，契约见 docs/04 4.6）
 *
 * `reason` 是**人话解释**：前端弹窗直接展示，不让前端自己算业务规则（R-15 的边界条件
 * 涉及"下一场会议 + 锁定缓冲 + 当日 20:00"三者取小，前端重算一遍就是重写一遍规则，必然有一天不一致）。
 */
public record ExtendLimitVO(long maxDelayMinutes, String reason) {
}
