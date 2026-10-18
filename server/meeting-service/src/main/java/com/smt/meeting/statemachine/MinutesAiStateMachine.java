package com.smt.meeting.statemachine;

import com.smt.meeting.enums.MinutesAiStatus;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 纪要 AI 状态机：{@code ai_status} 迁移的唯一合法性校验入口。
 *
 * <p>状态值见 {@link MinutesAiStatus}：0 未生成 / 10 待生成 / 20 生成中 / 30 成功 / 40 失败。
 * 允许的迁移：</p>
 * <ul>
 *   <li>0 → 10：首次请求生成</li>
 *   <li>10 → 20：worker 抢占开始生成</li>
 *   <li>20 → 30：生成成功</li>
 *   <li>20 → 10：失败但仍有重试次数</li>
 *   <li>20 → 40：失败且次数耗尽（或卡死超时终止）</li>
 *   <li>30 → 10 / 40 → 10：用户主动重新生成</li>
 * </ul>
 *
 * <p>与会议状态机不同，这里只做纯校验：真正的并发防线是 Mapper 里带前态条件的 CAS UPDATE
 * （影响 0 行 = 状态已被并发变更），本类不碰数据库。</p>
 */
@Component
public class MinutesAiStateMachine {

    private static final Set<String> ALLOWED = Set.of(
            "0->10", "10->20", "20->30", "20->10", "20->40", "30->10", "40->10");

    /**
     * 校验迁移是否合法，非法时抛出 {@link IllegalStateException}。
     *
     * <p>用 {@link MinutesAiStatus#labelOrRaw(int)} 输出状态名：即使 DB 里出现未知状态值，
     * 错误信息本身也不会因为查表失败再抛一次异常。</p>
     */
    public void assertTransition(int from, int to) {
        if (!ALLOWED.contains(from + "->" + to)) {
            throw new IllegalStateException(
                    "非法纪要 AI 状态迁移: " + MinutesAiStatus.labelOrRaw(from)
                            + " -> " + MinutesAiStatus.labelOrRaw(to));
        }
    }
}
