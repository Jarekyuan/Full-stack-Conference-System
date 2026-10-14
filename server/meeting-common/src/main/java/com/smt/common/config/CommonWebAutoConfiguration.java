package com.smt.common.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.smt.common.exception.GlobalExceptionHandler;
import com.smt.common.filter.UserHeaderFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

import java.time.format.DateTimeFormatter;

/**
 * common 模块 Web 自动配置（经 AutoConfiguration.imports 注册，各服务引入即生效）：
 * ① 全局异常处理 ② Jackson 时间格式统一 yyyy-MM-dd HH:mm:ss ③ 用户上下文过滤器 ④ 方法级鉴权
 * 限定 SERVLET 应用：WebFlux 应用（如 gateway）不会激活 Servlet 组件
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

    @Bean
    public FilterRegistrationBean<UserHeaderFilter> userHeaderFilter() {
        FilterRegistrationBean<UserHeaderFilter> registration = new FilterRegistrationBean<>(new UserHeaderFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        return registration;
    }
}
