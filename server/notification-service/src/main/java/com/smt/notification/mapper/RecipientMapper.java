package com.smt.notification.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

/**
 * 收件人解析（**只读**别人家的表，SQL 见 resources/mapper/RecipientMapper.xml）。
 *
 * <p>为什么是"读别人的表"而不是调别人的接口：当前是**单库**（docs/03 5.7），
 * 通知服务要的是"这场会现在有谁"（meeting_participant）和"谁是审批人"（sys_user_role），
 * 两条都是只读、且都是"取当前事实"——直接 SELECT 是最短路径。
 * 事件里不带参会人快照正是为了这个（参会人可改，T4.1 的设计判断）。
 *
 * <p>⚠️ **这是单库带来的债**，写在这里免得将来分库时被漏掉：
 * 按 docs/03 5.7 的归属，meeting_participant 属于 meeting-service、sys_user_role 属于 auth-service，
 * 分库后这两条 SQL 会直接失效。届时的替代方案是把这两处改成 Feign 调用（生产端服务提供内部接口），
 * 或者让事件携带收件人 id 列表（代价是快照可能过期）。**现在不做的理由**：为了一个尚未发生的分库，
 * 先给旁路通知链路加上跨服务调用 + 熔断/超时/降级，是把简单问题做复杂。
 */
public interface RecipientMapper {

    /** 会议参会人（不含组织人——组织人单独取，见事件里的 organiserId） */
    List<Long> selectParticipantIds(@Param("meetingId") Long meetingId);

    /** 全部启用状态的审批人（审批不分部门/会议室范围，见 docs/04 3.2 权限列） */
    List<Long> selectApproverIds();
}
