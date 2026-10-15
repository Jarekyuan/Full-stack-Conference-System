package com.smt.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smt.common.exception.BusinessException;
import com.smt.common.exception.GlobalExceptionHandler;
import com.smt.common.result.ErrorCode;
import com.smt.common.result.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CommonWebAutoConfigurationTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** SecurityContextHolder 是 ThreadLocal，测试之间必须清干净，否则用例互相污染 */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** 只为构造 MethodArgumentTypeMismatchException 提供一个"有具名 Long 形参"的方法 */
    @SuppressWarnings("unused")
    private void sampleEndpoint(Long id) {
        // 方法体无意义：仅作为反射目标
    }

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

    // ———— 鉴权失败的两副面孔（评审 P1-10）：匿名 = "没登录"（401），已登录 = "没权限"（403） ————

    @Test
    void accessDeniedWithoutAuthenticationShouldMapTo401NotInternalError() {
        // 无身份上下文（服务被直连、或网关没注入身份头）时方法鉴权必然失败——那是未认证，不是 500，也不是 403
        ResponseEntity<Result<Void>> response = handler.handleAccessDenied(new AccessDeniedException("denied"));
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(10002, response.getBody().getCode());
    }

    @Test
    void accessDeniedWithAnonymousTokenShouldMapTo401() {
        SecurityContextHolder.getContext().setAuthentication(anonymous());

        ResponseEntity<Result<Void>> response = handler.handleAccessDenied(new AccessDeniedException("denied"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(10002, response.getBody().getCode());
    }

    @Test
    void accessDeniedWithLoggedInUserShouldMapTo403() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(9L, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        ResponseEntity<Result<Void>> response = handler.handleAccessDenied(new AccessDeniedException("denied"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(10003, response.getBody().getCode());
        assertEquals("无权限执行此操作", response.getBody().getMsg());
    }

    // ———— 框架级异常不得吞成 500（评审 P1-11） ————

    @Test
    void typeMismatchShouldMapToParamError() throws Exception {
        Method method = CommonWebAutoConfigurationTest.class.getDeclaredMethod("sampleEndpoint", Long.class);
        MethodArgumentTypeMismatchException e =
                new MethodArgumentTypeMismatchException("abc", Long.class, "id", new MethodParameter(method, 0), null);

        Result<Void> result = handler.handleTypeMismatch(e);

        assertEquals(10001, result.getCode());
        assertEquals("参数 id 类型不正确", result.getMsg());
    }

    @Test
    void missingRequestParamShouldMapToParamError() {
        Result<Void> result = handler.handleMissingParam(new MissingServletRequestParameterException("week", "String"));

        assertEquals(10001, result.getCode());
        assertEquals("缺少必填参数 week", result.getMsg());
    }

    // 这两个用例只断言 body 的 code：HTTP 405/415 由方法上的 @ResponseStatus 声明式指定（见 GlobalExceptionHandler），
    // 不在单测里重复断言注解本身

    @Test
    void wrongHttpMethodShouldMapToMethodNotAllowedCode() {
        Result<Void> result = handler.handleMethodNotSupported(new HttpRequestMethodNotSupportedException("PUT"));

        assertEquals(10008, result.getCode());
        assertEquals("请求方法不支持", result.getMsg());
    }

    @Test
    void unsupportedMediaTypeShouldMapToUnsupportedMediaTypeCode() {
        Result<Void> result = handler.handleMediaTypeNotSupported(
                new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)));

        assertEquals(10009, result.getCode());
        assertEquals("不支持的请求内容类型", result.getMsg());
    }

    private static AnonymousAuthenticationToken anonymous() {
        return new AnonymousAuthenticationToken(
                "test-key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
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
