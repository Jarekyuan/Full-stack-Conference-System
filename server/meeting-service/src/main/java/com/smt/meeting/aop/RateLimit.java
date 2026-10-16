package com.smt.meeting.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 用户级滑动窗口限流（T4.8）。标在"会被刷"的写接口上（当前只有创建预约）。
 *
 * <p>为什么是**注解 + 切面**而不是在 Service 里写一段计数逻辑：
 * 限流是横切关注点，写进业务方法会污染主流程（预约方法已经够长了）；注解让"哪些接口被保护"
 * 一眼可见，也方便将来给别的接口加。
 *
 * <p>为什么**不做网关全局限流**（02 文档决策 Q3）：本项目没有高并发场景，网关层限流是"为了限流而限流"；
 * 真要防的是"某个用户脚本刷预约"这种**业务级**滥用，它需要业务身份（userId，网关注入），
 * 而不是 IP ——放网关反而拿不到这个维度。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * 业务名，用于拼 key（`limit:{value}:{userId}`，与 docs/02 6.4 的键设计一致）。
     * 同一个业务名共享一个计数器——将来给"取消/修改"也加限流时，给它们各自的业务名即可。
     */
    String value() default "reserve";
}
