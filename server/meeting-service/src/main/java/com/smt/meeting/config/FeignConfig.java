package com.smt.meeting.config;

import com.smt.common.context.UserContext;
import com.smt.common.filter.TraceIdFilter;
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
            // traceId 也一起带过去（T4.9）：不带的话下游服务只能自己造一个新的，
            // "全链路一个 id"就断在这一次跨服务调用上——而这种断法在日志里**看不出来**
            // （两段日志各自都正常，只是 id 对不上）。
            String traceId = TraceIdFilter.currentTraceId();
            if (traceId != null) {
                template.header(TraceIdFilter.HEADER_TRACE_ID, traceId);
            }
        };
    }
}
