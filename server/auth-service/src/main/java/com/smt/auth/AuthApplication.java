package com.smt.auth;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 认证与用户服务启动类（:8081）
 * 能力：登录 / 双 token 刷新 / 用户搜索与管理（T1.9-T1.12）
 */
@SpringBootApplication
@MapperScan("com.smt.auth.mapper")
public class AuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
