package com.smt.meeting.aop;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口幂等标记（T2.5）：配合请求头 {@code X-Idempotency-Token} 使用（docs/04 4.4）。
 *
 * 客户端在进入表单时生成一个 UUID，提交与"重试"复用同一个 token；
 * 服务端以 {@code SETNX(idem:{value}:{userId}:{token})} 判重：
 * 窗口内第二次提交直接返回 10006（请勿重复提交，前端按 04 文档 2.2 静默忽略）。
 *
 * 设计取舍：
 * ① 未携带 token 的请求**宽容放行**（前端按约定必带；后端不强制以免破坏既有联调），仅告警日志；
 * ② 业务执行失败时删除 key——允许用户改参数后带同一 token 重试（失败不是"已提交"）；
 *    成功则保留 key 至窗口结束，挡住网络重试/手抖的重复提交。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /** 幂等 key 的业务前缀（如 "meeting"），最终 key = idem:{value}:{userId}:{token} */
    String value();

    /** 幂等窗口（分钟）：窗口内同一 token 的重复提交被拦截 */
    int ttlMinutes() default 10;
}
