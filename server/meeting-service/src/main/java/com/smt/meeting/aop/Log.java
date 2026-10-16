package com.smt.meeting.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作审计（T4.11）：标在**关键写操作**上，由 {@link LogAspect} 环绕记录"谁在何时做了什么、成没成、多久"。
 *
 * <p>⚠️ 与 lombok 的 `lombok.extern.java.Log` **同名**：本项目一律用 `@Slf4j`（不用 lombok 的 @Log），
 * 引用时写全 `com.smt.meeting.aop.Log` 即可。选这个名字是按开发计划（05 文档 T4.11）来的。
 *
 * <h3>为什么不要 @Log 记全文</h3>
 * 记录里**只放对象 id**，不放 DTO 全文：请求体里可能有备注、参会人、将来的敏感字段，
 * 一股脑写进审计文件既膨胀又等于把数据复制到了另一个地方（审计**不是**备份）。
 *
 * <h3>⚠️ 自调用会失效（笔记 11 章的坑）</h3>
 * Spring AOP 基于代理：**同一个类内部的方法互调不走代理**，标注就没效果。
 * 所以：只标注"被 Controller 等外部调用"的公开方法（create/cancel/update/pass/reject…），
 * 不要标内部私有方法（比如 `applyUpdate`）——那里标了也不会生效，比不标更危险（以为有审计其实没有）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Log {

    /** 操作类型（写进审计行的 ACTION 字段；用常量字符串而不是枚举，方便跨服务/跨模块复用同一套词汇） */
    String type();

    /**
     * 目标 id **只认返回值**（创建类操作的参数里没有"被操作对象"的 id）。
     *
     * <p>为什么要有这个开关（2026-10-16 评审 O3）：`create(dto, organiserId)` 的参数里第一个 Long
     * 是**组织人 id**，失败时按"参数优先"兜底会记成 `target=组织人id`——审计员会当成"操作了那个会议"。
     * 开启它之后，创建失败就老老实实记 `target=-`（宁可缺一个字段，不可记错一个 id）。
     */
    boolean targetFromResultOnly() default false;
}
