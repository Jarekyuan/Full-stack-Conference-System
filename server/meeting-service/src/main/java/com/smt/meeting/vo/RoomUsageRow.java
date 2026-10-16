package com.smt.meeting.vo;

import lombok.Data;

/**
 * 会议室使用率的**SQL 投影行**（T5.3）：只有 SQL 真能查出来的四列。
 *
 * <p>⚠️ 刻意用**可变类**而不是 record（2026-10-16 实现时踩到两次）：
 * <ul>
 *   <li>让 MyBatis 填一个 SQL 里不存在的字段（如服务端算的 {@code usageRate}）→
 *       运行期 {@code IndexOutOfBoundsException: Index 4 out of bounds for length 4}；</li>
 *   <li>record 配**显式 resultMap** 也不行：MyBatis 的 resultMap 是**按 setter** 映射的
 *       （{@code ReflectionException: There is no setter for property named 'roomId'}），
 *       不可变类型要么走 {@code resultType}+列别名（构造器路径），要么就得是可写 Bean。</li>
 * </ul>
 * 本项目的规矩是"SQL 显式 resultMap"（AI-RULES 第十一章），所以这里选可变 Bean；
 * 派生值（usageRate）在 Service 里补——**mapper 的返回类型只放 SQL 真查得到的东西**。
 */
@Data
public class RoomUsageRow {

    private Long roomId;
    private String roomName;
    private long meetingCount;
    private long totalMinutes;
}
