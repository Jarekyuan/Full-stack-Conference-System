package com.smt.meeting.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 定时任务总开关（T3.5）与调度线程池。
 *
 * <p>只做两件事：打开 {@code @Scheduled} 的处理；提供两个<b>互相隔离</b>的调度器。</p>
 *
 * <ul>
 *   <li>{@code taskScheduler}（单线程）：会议状态流转/提醒等既有任务，保持原有的串行语义；</li>
 *   <li>{@code minutesTaskScheduler}（4 线程）：纪要相关任务（上传会话清理、AI worker、ASR worker）。
 *       这类任务会做最多 50 次对象存储 abort（远端调用，可能很慢），
 *       不能把单线程的会议调度器堵住。</li>
 * </ul>
 *
 * <p>显式声明名为 {@code taskScheduler} 的 Bean 还有一个作用：当存在多个 TaskScheduler 时，
 * 未指定 {@code scheduler} 的 {@code @Scheduled} 方法优先使用这个同名 Bean，
 * 从而保证既有任务仍落在单线程池里，而不是被分到 minutes 池。</p>
 */
@Configuration
@EnableScheduling
public class ScheduleConfig {

    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("scheduling-");
        return scheduler;
    }

    @Bean(name = "minutesTaskScheduler")
    public ThreadPoolTaskScheduler minutesTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // AI 调用和 ASR 轮询都可能阻塞数秒；2 线程会被单个慢供应商拖住，
        // 4 线程保证清理/AI/ASR 至少各有可用线程。
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("minutes-task-");
        return scheduler;
    }
}
