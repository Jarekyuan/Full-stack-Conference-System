package com.smt.notification;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 通知服务启动类（:8083）
 * 能力：消费会议事件 → 站内信（T4.3/T4.4）/ 会议提醒（T4.5）/ WebSocket 推送（T4.6）
 *
 * <p>包名 {@code com.smt.notification} 与 meeting-common 的自动配置无冲突：
 * 公共能力（Result/ErrorCode/UserContext/全局异常）由 common 的 AutoConfiguration 提供，
 * 这里只扫描本服务的 Bean。
 */
@SpringBootApplication
@MapperScan("com.smt.notification.mapper")
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
