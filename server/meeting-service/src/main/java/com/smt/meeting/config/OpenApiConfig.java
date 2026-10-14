package com.smt.meeting.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc 接口文档配置（Swagger UI: http://localhost:8082/swagger-ui/index.html）
 * 接口按 Controller 自动分组为 tag
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI meetingApiOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("会议室预约系统 · meeting-service")
                .description("会议室 / 预约 / 审批接口（对照 docs/04 接口设计）")
                .version("v1.0.0"));
    }
}
