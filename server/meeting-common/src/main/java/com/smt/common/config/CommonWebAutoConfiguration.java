package com.smt.common.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.smt.common.exception.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.format.DateTimeFormatter;

/**
 * common 模块 Web 自动配置（经 AutoConfiguration.imports 注册，各服务引入即生效）：
 * ① 全局异常处理 ② Jackson 时间格式统一 yyyy-MM-dd HH:mm:ss（docs/04 接口设计 1.5）
 */
@AutoConfiguration
@ConditionalOnWebApplication
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
}
