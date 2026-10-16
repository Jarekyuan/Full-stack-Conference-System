package com.smt.notification.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.smt.common.mq.MeetingDomainEvent;
import com.smt.common.mq.MeetingEventType;
import com.smt.notification.entity.Notification;
import com.smt.notification.enums.NotificationType;
import com.smt.notification.mapper.NotificationMapper;
import com.smt.notification.mapper.RecipientMapper;

/**
 * 通知生成（T4.3）：把一条会议事件翻译成"N 条站内信"。
 *
 * <p>收件人规则严格按 docs/04 5.3 的通知类型表（谁收到什么，是产品定的，不是这里发挥的）：
 * <pre>
 *   MEETING_CREATED   → 组织人
 *   APPROVAL_TODO     → 审批人（凡是事件之后落在"待审批"的都发：创建审批房、改期回 10）
 *   APPROVAL_RESULT   → 组织人
 *   MEETING_UPDATED   → 参会人
 *   MEETING_CANCELLED → 参会人
 * </pre>
 *
 * <p><b>幂等在哪一层</b>：这一层不管幂等——它只负责"给定事件，生成该有的通知"。
 * 去重在消费者（{@code MeetingEventConsumer} + {@code ConsumeIdempotency}）按 msgId 做，
 * 两者职责分开：这里改文案、改收件人规则，不会碰到去重逻辑；反过来也一样。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    /** notification.content 列宽（db/01-schema.sql） */
    private static final int CONTENT_MAX_LENGTH = 500;
    /** hutool 的 maxLength 会**额外**追加 "..."，所以预算里要先把省略号扣掉（踩坑 #10） */
    private static final int ELLIPSIS_LENGTH = 3;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final NotificationMapper notificationMapper;
    private final RecipientMapper recipientMapper;

    /**
     * 处理一条会议事件（消费者在**重试模板内**调用，失败会整体重试；故本方法必须可重入）。
     *
     * <p>`@Transactional`：一次事件产生的 N 条通知要么都写、要么都不写。
     * 半截状态（给 3 个参会人发了、另 2 个没发）比"整条消息重投一次"更难排查。
     */
    @Transactional
    public void handle(MeetingDomainEvent event) {
        if (event == null || event.type() == null) {
            throw new IllegalArgumentException("事件缺少类型，无法生成通知");
        }
        switch (event.type()) {
            case CREATED -> onCreated(event);
            case UPDATED -> onUpdated(event);
            case APPROVED, REJECTED -> onApprovalResult(event);
            case CANCELLED -> onCancelled(event);
            // REMIND（T4.5 接线）与将来新增的事件类型
            default -> log.warn("[通知] 未接线的事件类型，已忽略: type={}, meetingId={}", event.type(), event.meetingId());
        }
    }

    // ---------- 各场景 ----------

    /** 创建：组织人收"已提交/已确认"；需审批时另发一条待办给审批人 */
    private void onCreated(MeetingDomainEvent e) {
        boolean pending = e.pendingApproval();
        String content = "您预约的「" + e.title() + "」（" + timeRange(e) + "）"
                + (pending ? "已提交，等待审批。" : "已确认。");
        save(NotificationType.MEETING_CREATED, pending ? "预约已提交" : "预约成功", content, e,
                List.of(e.organiserId()));
        if (pending) {
            notifyApprovers(e);
        }
    }

    /**
     * 修改：参会人收"信息变更"；若改期改回了待审批（R-14），审批人再收一条待办
     * ——否则那场会议会静静地躺在待办箱里没人知道。
     */
    private void onUpdated(MeetingDomainEvent e) {
        String content = "「" + e.title() + "」（" + timeRange(e) + "）信息已更新，请留意。";
        save(NotificationType.MEETING_UPDATED, "会议信息变更", content, e,
                recipientMapper.selectParticipantIds(e.meetingId()));
        if (e.pendingApproval()) {
            notifyApprovers(e);
        }
    }

    /** 审批结果：组织人收通过/拒绝（带审批意见） */
    private void onApprovalResult(MeetingDomainEvent e) {
        boolean approved = e.type() == MeetingEventType.APPROVED;
        StringBuilder content = new StringBuilder()
                .append("您预约的「").append(e.title()).append("」（").append(timeRange(e)).append("）")
                .append(approved ? "已通过审批。" : "被拒绝。");
        if (StrUtil.isNotBlank(e.comment())) {
            content.append("审批意见：").append(e.comment());
        }
        save(NotificationType.APPROVAL_RESULT, approved ? "审批通过" : "审批被拒", content.toString(), e,
                List.of(e.organiserId()));
    }

    /** 取消：收件人只取参会人（docs/04 5.3）——取消一般是组织人自己发起的，不必再通知自己一遍 */
    private void onCancelled(MeetingDomainEvent e) {
        StringBuilder content = new StringBuilder()
                .append("「").append(e.title()).append("」（").append(timeRange(e)).append("）已取消。");
        if (StrUtil.isNotBlank(e.comment())) {
            content.append("取消原因：").append(e.comment());
        }
        save(NotificationType.MEETING_CANCELLED, "会议已取消", content.toString(), e,
                recipientMapper.selectParticipantIds(e.meetingId()));
    }

    private void notifyApprovers(MeetingDomainEvent e) {
        String content = "「" + e.title() + "」（" + timeRange(e) + "）等待审批。";
        save(NotificationType.APPROVAL_TODO, "待审批会议", content, e, recipientMapper.selectApproverIds());
    }

    // ---------- 内部 ----------

    /**
     * 落库一条通知的 N 个副本（每个收件人一行）。
     *
     * <p>去重与空值在这里收口：收件人集合里重复 id 只发一条（LinkedHashSet，顺带保序便于断言），
     * organiserId 可能为 null（系统任务产生的历史数据）→ 直接跳过，不写出一条 userId=null 的脏通知。
     */
    private void save(NotificationType type, String title, String content, MeetingDomainEvent e, List<Long> recipients) {
        LinkedHashSet<Long> distinct = new LinkedHashSet<>();
        for (Long userId : recipients) {
            if (userId != null) {
                distinct.add(userId);
            }
        }
        if (distinct.isEmpty()) {
            log.debug("[通知] 无收件人，跳过: type={}, meetingId={}", type, e.meetingId());
            return;
        }
        String safeContent = clamp(content);
        List<Notification> rows = new ArrayList<>(distinct.size());
        for (Long userId : distinct) {
            Notification n = new Notification();
            n.setUserId(userId);
            n.setType(type.name());
            n.setTitle(title);
            n.setContent(safeContent);
            n.setMeetingId(e.meetingId());
            rows.add(n);
        }
        notificationMapper.insertBatch(rows);
    }

    /**
     * 截断到列宽。
     *
     * <p>为什么要截：文案里嵌了**会议标题（用户输入，最长 100）**与**审批意见/取消原因（最长 500）**，
     * 拼出来完全可能超过 content 的 500。严格模式下超宽 = `1406 Data too long`，
     * 一次事件写不进去会**连整批通知一起回滚**（M3 的审批意见踩过同一个坑，见 CLAUDE.md 踩坑 #10）。
     * 在"列宽的所有者"这里截断，调用方以后再加多少场景都不会漏。
     */
    private String clamp(String content) {
        if (content == null || content.length() <= CONTENT_MAX_LENGTH) {
            return content;
        }
        return StrUtil.maxLength(content, CONTENT_MAX_LENGTH - ELLIPSIS_LENGTH);
    }

    /** 会议时间段文案：MM-dd HH:mm~HH:mm（同一天只写一次日期） */
    private String timeRange(MeetingDomainEvent e) {
        LocalDateTime start = e.startTime();
        LocalDateTime end = e.endTime();
        if (start == null) {
            return "时间待定";
        }
        return end == null ? TIME_FORMAT.format(start)
                : TIME_FORMAT.format(start) + "~" + TIME_FORMAT.format(end).substring(6);
    }
}
