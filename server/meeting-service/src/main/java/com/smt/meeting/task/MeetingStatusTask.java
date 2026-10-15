package com.smt.meeting.task;

import com.smt.common.exception.BusinessException;
import com.smt.meeting.enums.MeetingEventEnum;
import com.smt.meeting.mapper.MeetingMapper;
import com.smt.meeting.statemachine.MeetingStateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.stream.Stream;

/**
 * 会议生命周期定时任务（T3.5 状态流转 + T3.6 对账补偿）。
 *
 * <h3>三个设计要点</h3>
 * ① **幂等**：每条的迁移走状态机 CAS（`WHERE status = 前态`），重复执行不会有副作用；
 *    状态已被别人改走的行按"跳过"处理（不是错误——那正是 CAS 该有的表现）。
 * ② **多实例只跑一份**：Redisson 锁 `task:meeting:status` 包住整轮，两个任务共用同一把。
 *    抢不到锁就**直接跳过本轮**（waitTime=0），而不是排队等待——排队等到的第二轮会立刻又被下一轮抢走，
 *    除了堆积调度线程没有任何好处。
 * ③ **分批**：每批 {@link #BATCH_SIZE} 条、最多 {@link #MAX_BATCHES} 批，避免一次扫全表 + 长事务；
 *    每条的迁移是**独立事务**（状态机方法自带 @Transactional），一条失败不影响同批其他条。
 *
 * <h3>为什么不用"条件 UPDATE 批量改状态"（docs/05 T3.5 原文的写法）</h3>
 * 那样一行 SQL 就能改完一批，但会**绕开状态机**——CLAUDE.md 红线 #4 与 AI-RULES 都禁止裸 UPDATE 改 status：
 * 状态变更必须同时写 `meeting_status_log`（谁在什么时候因为什么改的），否则详情页的时间线会缺一段。
 * 计划文档写在 M2 状态机落地之前，实现时按红线为准。（真需要批量 UPDATE 时，
 * 正确做法是"批量 UPDATE + 批量补日志"一起做，而不是只牺牲日志。）
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "meeting.task.status.enabled", havingValue = "true", matchIfMissing = true)
public class MeetingStatusTask {

    /** 任务锁 key（两个任务共用：都是写状态，没必要并行） */
    public static final String LOCK_KEY = "task:meeting:status";
    /** 每批处理条数 */
    private static final int BATCH_SIZE = 200;
    /** 单轮最多处理批数（防某次数据异常时无限循环下去，把调度线程钉死） */
    private static final int MAX_BATCHES = 20;
    /** 对账告警里最多列几个 id（列全了反而看不出重点，剩下的只报数量） */
    private static final int LOG_ID_LIMIT = 20;

    private final MeetingMapper meetingMapper;
    private final MeetingStateMachine stateMachine;
    private final RedissonClient redissonClient;

    /** 状态流转：默认每分钟一轮；间隔可用 `meeting.task.status.delay-ms` 覆盖（验证脚本会调小它） */
    @Scheduled(fixedDelayString = "${meeting.task.status.delay-ms:60000}")
    public void advanceStatus() {
        withTaskLock("状态流转", () -> {
            int started = advance(MeetingEventEnum.START, "开始", limit -> meetingMapper.selectIdsToStart(limit)).size();
            int finished = advance(MeetingEventEnum.END, "结束", limit -> meetingMapper.selectIdsToFinish(limit)).size();
            if (started + finished > 0) {
                log.info("[定时任务] 状态流转完成：转进行中 {} 场，转已结束 {} 场", started, finished);
            }
        });
    }

    /**
     * 对账补偿（T3.6 / E-4）：默认每小时一次，兜底"时间早过了、状态却没推进"的异常数据。
     *
     * <p>什么时候会有这种数据：任务停摆（服务重启期间错过、异常被吞、多实例互斥锁长期没释放……）。
     * 所以修复本身要打 **ERROR**：它不是"日常维护"，而是**告警线索**——
     * 出现即说明正常路径漏了东西，需要人去查（日志里带 id 清单）。
     *
     * <p>时间阈值（2 小时）写在 SQL 里，口径统一走 DB 时钟（见 Mapper 注释）；
     * 阈值足够宽，不会和"刚刚才到点、正常任务正在处理"的记录抢同一行。
     */
    @Scheduled(cron = "${meeting.task.reconcile.cron:0 5 * * * ?}")
    public void reconcileStuck() {
        withTaskLock("状态对账", () -> {
            List<Long> fixedStart = advance(MeetingEventEnum.START, "开始(对账)",
                    limit -> meetingMapper.selectStuckIdsToStart(limit));
            List<Long> fixedFinish = advance(MeetingEventEnum.END, "结束(对账)",
                    limit -> meetingMapper.selectStuckIdsToFinish(limit));
            if (!fixedStart.isEmpty() || !fixedFinish.isEmpty()) {
                List<Long> all = Stream.concat(fixedStart.stream(), fixedFinish.stream()).toList();
                // ERROR 而不是 INFO：这不是"日常维护"，是**告警线索**——正常轮询每分钟一次，
                // 还能攒出 2 小时没推进的记录，说明任务曾停摆或异常，需要人去查
                log.error("[对账] 修复 {} 场状态未推进的会议（转进行中 {} / 转已结束 {}），id 清单{}{}",
                        all.size(), fixedStart.size(), fixedFinish.size(),
                        all.stream().limit(LOG_ID_LIMIT).toList(),
                        all.size() > LOG_ID_LIMIT ? "（仅列前 " + LOG_ID_LIMIT + " 条）" : "");
            }
            // 与上面的"修复"相互独立：待审批超时是另一类数据，修不修得到别的都不影响它的可见性
            warnStuckPending();
        });
    }

    /**
     * 待审批超时检测（只告警、不自动修）。
     *
     * <p>为什么单独拎出来：status=10 **既不占时段、也不在自动流转的覆盖范围内**——
     * 20/30 有正常轮询兜着，10 没有任何路径能收尾。"没人批 + 时间已过"的申请会永远留在审批人的待办箱里，
     * 而它对应的时段其实早就没意义了（时间都过去了）。这一类才是对账真正能发现、且**只有它能发现**的东西。
     *
     * <p>为什么只告警不自动修：把它改成什么状态是**产品决策**（自动拒绝 60？自动作废 50？还是保留待办？），
     * 不该由一段对账代码替产品做主——代码只负责让这类数据**可见**（ERROR + id 清单），由人决定怎么收尾。
     */
    private void warnStuckPending() {
        List<Long> stuck = meetingMapper.selectStuckPending(LOG_ID_LIMIT);
        if (stuck.isEmpty()) {
            return;
        }
        log.error("[对账] 发现 {} 场【待审批但时间已过】的会议（仅列前 {} 条）：{}——"
                        + "10 不占时段、也不在自动流转范围内，这类申请不会有任何路径自动收尾；"
                        + "当前只告警不自动修，收尾策略（自动拒绝/作废）需产品决策，见 docs/05 T3.6",
                stuck.size(), LOG_ID_LIMIT, stuck);
    }

    // ---------- 内部 ----------

    /**
     * 抢任务锁执行（抢不到直接跳过本轮）。
     * 两个任务共用：对账与流转写的都是同一批状态，并行只会互相抢行锁。
     */
    private void withTaskLock(String label, Runnable body) {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired;
        try {
            // waitTime=0（抢不到就走）+ 不指定 leaseTime → 走看门狗自动续期：
            // 任务耗时随数据量变化，写死一个 leaseTime 迟早会在某次大扫除时提前过期，
            // 让第二实例同时进场——那正是这把锁要防的事
            acquired = lock.tryLock(0, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 保持中断标志，交给线程池处理
            return;
        }
        if (!acquired) {
            log.info("[定时任务] {} 未抢到 {}，本轮跳过（多实例互斥：另一实例正在执行）", label, LOCK_KEY);
            return;
        }
        try {
            body.run();
        } catch (Exception e) {
            // 兜底：异常会被调度器吞掉再重排，但日志里看不到"这一轮到底做了什么"，
            // 所以自己兜住并打清楚（下一轮照常执行，不会因此停摆）
            log.error("[定时任务] {} 异常", label, e);
        } finally {
            // isHeldByCurrentThread 判断不能省：若看门狗因故没续上、锁已易主，
            // 直接 unlock 会抛 IllegalMonitorStateException，把 finally 自己变成异常源
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 按事件推进到点的会议；返回**实际迁移成功的 id 清单**（调用方按需取 size 或打日志）。
     *
     * @param fetchBatch 取一批待处理 id 的取法（正常轮询 / 对账两种时间条件共用这套推进逻辑）
     */
    private List<Long> advance(MeetingEventEnum event, String label, IntFunction<List<Long>> fetchBatch) {
        List<Long> changed = new ArrayList<>();
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            List<Long> ids = fetchBatch.apply(BATCH_SIZE);
            if (ids.isEmpty()) {
                break;
            }
            for (Long meetingId : ids) {
                if (transitionQuietly(meetingId, event, label)) {
                    changed.add(meetingId);
                }
            }
            if (ids.size() < BATCH_SIZE) {
                break; // 最后一批不满，说明已经扫完
            }
        }
        return changed;
    }

    /**
     * 单条迁移：CAS 失败 = 状态已被别人改走，按跳过处理，不算错误。
     * operatorId 传 null：系统触发的变更在时间线上显示为"系统"（前端已兜底）。
     */
    private boolean transitionQuietly(Long meetingId, MeetingEventEnum event, String label) {
        try {
            stateMachine.transition(meetingId, event, null);
            log.debug("[定时任务] 会议 {} {}完成", meetingId, label);
            return true;
        } catch (BusinessException e) {
            log.debug("[定时任务] 会议 {} {}跳过（状态已变化：{}）", meetingId, label, e.getMessage());
            return false;
        }
    }
}
