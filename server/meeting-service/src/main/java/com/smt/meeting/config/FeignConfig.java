package com.smt.meeting.config;

import cn.hutool.core.util.StrUtil;
import com.smt.common.context.UserContext;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 配置：拦截器把 ThreadLocal 用户上下文透传到下游服务
 * （下游 UserHeaderFilter 依赖 X-User-Id/X-User-Role 恢复上下文）
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
            String role = UserContext.getRole();
            if (StrUtil.isNotBlank(role)) {
                template.header("X-User-Role", role);
            }
        };
    }
}
