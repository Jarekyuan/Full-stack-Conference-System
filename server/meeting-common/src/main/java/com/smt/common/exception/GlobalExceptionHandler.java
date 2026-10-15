package com.smt.common.exception;

import com.smt.common.result.ErrorCode;
import com.smt.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理：业务错误返回 HTTP 200 + code（约定见 docs/04 接口设计 1.3）；
 * 未捕获异常返回 500 且不向客户端暴露堆栈。由 common 自动配置注册，各服务免声明。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Object> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, msg={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage(), e.getData());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidationException(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + " " + fieldError.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", detail);
        return Result.error(ErrorCode.PARAM_ERROR.getCode(), detail.isEmpty() ? ErrorCode.PARAM_ERROR.getMsg() : detail);
    }

    /**
     * 请求体不可读（JSON 语法错 / 字段类型不可转换，如时间格式非法）：
     * 按参数错误映射为 10001——落到 Exception 兜底会变成 500/10000，误导调用方以为服务端故障（评审 P1-11 修复）。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.error(ErrorCode.PARAM_ERROR.getCode(), "请求体格式错误（检查字段类型，如时间应为 yyyy-MM-dd HH:mm:ss）");
    }

    /**
     * 裸参数类型错（`?status=abc`、路径变量 `/rooms/abc` 转 Long 失败）：按参数错误返回 10001。
     * 落到兜底会变成 500/10000，把一个"调用方写错参数"伪装成服务端故障（评审 P1-11 收口）。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型错误: name={}, value={}", e.getName(), e.getValue());
        return Result.error(ErrorCode.PARAM_ERROR.getCode(), "参数 " + e.getName() + " 类型不正确");
    }

    /** 缺少必填的 query 参数（如 `?week=` 没传）：同属参数校验失败 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少必填参数: {}", e.getParameterName());
        return Result.error(ErrorCode.PARAM_ERROR.getCode(), "缺少必填参数 " + e.getParameterName());
    }

    /** 表单/查询对象绑定失败（非 @RequestBody 路径的校验入口，与 MethodArgumentNotValid 同族） */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + " " + fieldError.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数绑定失败: {}", detail);
        return Result.error(ErrorCode.PARAM_ERROR.getCode(), detail.isEmpty() ? ErrorCode.PARAM_ERROR.getMsg() : detail);
    }

    /** HTTP 方法用错（GET 打 POST 接口）：协议层语义 —— 真 405 + 10008 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持: {}", e.getMessage());
        return Result.error(ErrorCode.METHOD_NOT_ALLOWED);
    }

    /** Content-Type 缺失/不匹配（写接口没带 application/json）：协议层语义 —— 真 415 + 10009 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public Result<Void> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.warn("内容类型不支持: {}", e.getMessage());
        return Result.error(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    /** 路径/静态资源不存在：框架级 404 语义，不能被下方兜底误伤成 500 */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("路径不存在: {}", e.getResourcePath());
        return Result.error(ErrorCode.NOT_FOUND);
    }

    /**
     * 方法级鉴权失败（@PreAuthorize 不通过）：映射为框架级 403，不能被兜底误伤成 500。
     * 但要**区分匿名**（评审 P1-10）：匿名上下文拿到的 AccessDenied 实际是"没登录"，
     * 回 403 会让前端只弹"无权限"、不触发重新登录——用户带着过期 token 点任何 ADMIN 功能都出不来登录页。
     * 语义对齐 Spring Security 的 ExceptionTranslationFilter：匿名 → 401（交给前端刷新/跳登录），已登录 → 403。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDenied(AccessDeniedException e) {
        // 状态码按"是否匿名"分叉，故用 ResponseEntity 而非方法级 @ResponseStatus（后者是静态的）
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth instanceof AnonymousAuthenticationToken) {
            log.warn("匿名访问受限资源: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.error(ErrorCode.UNAUTHORIZED));
        }
        log.warn("无权限访问: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.error(ErrorCode.NO_PERMISSION));
    }

    /** 未认证（无身份上下文时触发方法鉴权）：映射为框架级 401 */
    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> handleAuthentication(AuthenticationException e) {
        log.warn("未认证: {}", e.getMessage());
        return Result.error(ErrorCode.UNAUTHORIZED);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.error(ErrorCode.SYSTEM_ERROR);
    }
}
