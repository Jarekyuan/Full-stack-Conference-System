package com.smt.meeting.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务总开关（T3.5）
 *
 * 只做一件事：打开 `@Scheduled` 的处理。（不用 `@EnableAsync`——任务里没有需要异步化的东西，
 * 多线程调度反而会让两个任务在同一实例内并发跑，把"多实例互斥"这件事变复杂。）
 *
 * 调度线程数保持默认 1：同一实例内 start/finish 两阶段串行执行，跨实例互斥交给 Redisson 锁
 * （见 {@link com.smt.meeting.task.MeetingStatusTask}）。
 */
@Configuration
@EnableScheduling
public class ScheduleConfig {
}
