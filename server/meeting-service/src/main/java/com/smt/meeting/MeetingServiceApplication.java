package com.smt.meeting;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 会议核心服务启动类（:8082）
 * 能力：会议室 / 预约 / 审批 / 状态机 / 定时任务（M2-M3）
 */
@SpringBootApplication
@EnableFeignClients
@MapperScan("com.smt.meeting.mapper")
public class MeetingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MeetingServiceApplication.class, args);
    }
}
