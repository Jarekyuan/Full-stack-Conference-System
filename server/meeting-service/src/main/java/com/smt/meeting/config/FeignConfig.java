package com.smt.meeting.config;

import com.smt.common.context.UserContext;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Feign 配置：拦截器把 ThreadLocal 用户上下文透传到下游服务
 * （下游 UserHeaderFilter 依赖 X-User-Id/X-User-Roles 恢复上下文；v1.3 多角色）
 */
@Configuration
public class FeignConfig {

    @Bean
    public RequestInterceptor userContextInterceptor() {
        return template -> {
            Long userId = UserContext.getUserId();
            if (userId != null) {
                template.header("X-User-Id", String.valueOf(userId));
            }
            List<String> roles = UserContext.getRoles();
            if (!roles.isEmpty()) {
                template.header("X-User-Roles", String.join(",", roles));
            }
        };
    }
}
