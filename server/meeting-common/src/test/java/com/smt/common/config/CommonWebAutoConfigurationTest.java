package com.smt.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smt.common.exception.BusinessException;
import com.smt.common.exception.GlobalExceptionHandler;
import com.smt.common.result.ErrorCode;
import com.smt.common.result.Result;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommonWebAutoConfigurationTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void businessExceptionShouldMapToResultWithCodeAndData() {
        BusinessException e = new BusinessException(ErrorCode.TIME_CONFLICT, "conflicts");
        Result<Object> result = handler.handleBusinessException(e);
        assertEquals(40001, result.getCode());
        assertEquals("所选时段与已有会议冲突", result.getMsg());
        assertEquals("conflicts", result.getData());
    }

    @Test
    void unexpectedExceptionShouldMapToSystemErrorWithoutDetail() {
        Result<Void> result = handler.handleException(new RuntimeException("boom"));
        assertEquals(10000, result.getCode());
        assertEquals("系统内部错误", result.getMsg());
    }

    @Test
    void noResourceFoundShouldMapTo404NotInternalError() {
        NoResourceFoundException e = new NoResourceFoundException(HttpMethod.POST, "auth/login");
        Result<Void> result = handler.handleNoResourceFound(e);
        assertEquals(10004, result.getCode());
        assertEquals("资源不存在", result.getMsg());
    }

    @Test
    void localDateTimeShouldUseUnifiedPattern() throws Exception {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new CommonWebAutoConfiguration().jacksonDateTimeCustomizer().customize(builder);
        ObjectMapper mapper = builder.build();

        LocalDateTime time = LocalDateTime.of(2026, 10, 15, 14, 0, 0);
        assertEquals("\"2026-10-15 14:00:00\"", mapper.writeValueAsString(time));
        assertEquals(time, mapper.readValue("\"2026-10-15 14:00:00\"", LocalDateTime.class));
    }
}
