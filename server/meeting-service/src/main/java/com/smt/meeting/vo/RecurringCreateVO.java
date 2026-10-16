package com.smt.meeting.vo;

import java.util.List;

/**
 * 周期会议创建结果（docs/04 4.9）：
 * 返回所有场次 id 是**有意**的——前端要能立刻把这一批画到日历上，也让验证脚本不必反查库。
 */
public record RecurringCreateVO(Long recurringId, int created, List<Long> meetingIds) {
}
