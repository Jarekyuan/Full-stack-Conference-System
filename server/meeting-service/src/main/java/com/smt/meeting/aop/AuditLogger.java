package com.smt.meeting.aop;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 审计落盘（T4.11）：独立 logger + 独立 appender（`logs/audit.log`，见 logback-spring.xml）。
 *
 * <p>为什么单独抽一个组件（而不是让切面直接打日志）：**为了可测**。
 * "审计组件抛异常不能阻断业务"是本任务的验收项之一，而"让一个静态 logger 抛异常"在单测里做不到；
 * 抽成可注入的 Bean 之后，单测可以 mock 它在记录时抛错，确定性地验证"业务照常返回"。
 *
 * <p>为什么用独立文件：审计与业务日志的**留存期、查看方式、合规要求**都不一样
 * （业务日志按天滚动、排障时看；审计日志要长期保留、只追加）。混在一起，将来谁也分不出来。
 */
@Component
public class AuditLogger {

    /** 独立 logger 名（logback 里为它单独配 appender；行内也带 AUDIT 便于 grep） */
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 记一行审计。
     *
     * @param operatorId 操作人（网关注入的身份）
     * @param type       操作类型（{@link Log#type()}）
     * @param targetId   对象 id（可能拿不到：创建场景要看返回值、或参数里本来就没有）
     * @param success    成功/失败
     * @param elapsedMs  耗时（含事务）
     * @param error      失败原因（成功时传 null）——只记异常**类型与消息**，不记堆栈（审计不是排障日志）
     */
    public void record(Long operatorId, String type, Long targetId, boolean success,
                       long elapsedMs, String error) {
        AUDIT.info("AUDIT operator={} action={} target={} result={} cost={}ms time={} error={}",
                operatorId, type, targetId == null ? "-" : targetId,
                success ? "SUCCESS" : "FAIL", elapsedMs, TS.format(LocalDateTime.now()),
                error == null ? "-" : error);
    }
}
