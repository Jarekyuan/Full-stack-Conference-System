package com.smt.common.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.smt.common.exception.GlobalExceptionHandler;
import com.smt.common.filter.TraceIdFilter;
import com.smt.common.filter.UserHeaderFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

import java.time.format.DateTimeFormatter;

/**
 * common 模块 Web 自动配置（经 AutoConfiguration.imports 注册，各服务引入即生效）：
 * ① 全局异常处理 ② Jackson 时间格式统一 ③ 用户上下文过滤器 ④ 方法级鉴权 ⑤ 放行式安全过滤链
 * 限定 SERVLET 应用：WebFlux 应用（如 gateway）不会激活 Servlet 组件
 *
 * 安全模型（docs/02 2.2）：鉴权在网关（JWT 校验 + 身份头注入）+ 服务内 @PreAuthorize 方法级校验；
 * 服务内不做会话/表单登录。注意：UserHeaderFilter 必须挂在 SecurityFilterChain 内部
 * （SecurityContextHolderFilter 之后）——挂在链外会被其重置上下文导致身份丢失。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableMethodSecurity
@Import(GlobalExceptionHandler.class)
public class CommonWebAutoConfiguration {

    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonDateTimeCustomizer() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
        return builder -> builder
                .serializers(new LocalDateTimeSerializer(formatter))
                .deserializers(new LocalDateTimeDeserializer(formatter));
    }

    /**
     * traceId 过滤器（T4.9）：注册为**独立的** FilterRegistrationBean 而不是普通 @Bean——
     * 普通 Filter bean 由 Boot 以默认顺序（LOWEST_PRECEDENCE）注册，会排在安全链之后，
     * 那样"鉴权失败"这类最早的日志就还没有 traceId（恰恰是最需要它的场景）。
     */
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("traceIdFilter");
        return registration;
    }

    @Bean
    public UserHeaderFilter userHeaderFilter() {
        return new UserHeaderFilter();
    }

    /** 放行式安全链：无状态、无 CSRF/表单登录；访问控制由网关 + @PreAuthorize 承担 */
    @Bean
    public SecurityFilterChain permissiveSecurityFilterChain(HttpSecurity http, UserHeaderFilter userHeaderFilter)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(registry -> registry.anyRequest().permitAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 身份桥接：置于 SecurityContextHolderFilter 之后、AnonymousAuthenticationFilter 之前
                .addFilterAfter(userHeaderFilter, SecurityContextHolderFilter.class);
        return http.build();
    }
}
