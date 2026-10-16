package com.smt.meeting.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 限流阈值（T4.8）：放在 **Nacos 配置中心**，改值**不重启**即时生效。
 *
 * <p>{@code @RefreshScope} 是这里的关键：Nacos 配置变更 → Spring Cloud 发 {@code RefreshEvent} →
 * 本 Bean 被销毁重建（重新绑定配置）→ 下一次访问就拿到新阈值。
 * 没有它的话，配置虽然拉下来了，Bean 里的字段还是启动时的旧值（"改了没反应"的经典坑）。
 *
 * <p>⚠️ 刷新的是**本 Bean**：切面必须每次从它读阈值，不能把阈值在自己的字段里缓存一份
 * （那就又把"热更新"退回了"下次启动才生效"）。见 {@code RateLimitAspect}。
 *
 * <p>Nacos 里对应的 dataId 是 {@code meeting-service.yml}（见 application.yml 的 spring.config.import）：
 * <pre>
 * meeting:
 *   ratelimit:
 *     reserve-per-minute: 10
 * </pre>
 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "meeting.ratelimit")
public class RateLimitProperties {

    /**
     * 每个用户每分钟允许的预约请求数。
     * **≤ 0 表示关闭限流**（不是"全拒绝"）——演示/压测/并发脚本需要临时关掉它，
     * 用"设 0 关闭"比"改代码或停服务"现实得多（验证脚本就是这么做的）。
     */
    private int reservePerMinute = 10;

    /** 窗口长度（秒）：与"每分钟"的语义配套，单独可配是为了将来改成"每 10 秒"之类的短窗口 */
    private int windowSeconds = 60;
}
