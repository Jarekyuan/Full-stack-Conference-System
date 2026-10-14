package com.smt.common.exception;

import com.smt.common.result.ErrorCode;
import com.smt.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
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

    /** 路径/静态资源不存在：框架级 404 语义，不能被下方兜底误伤成 500 */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("路径不存在: {}", e.getResourcePath());
        return Result.error(ErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.error(ErrorCode.SYSTEM_ERROR);
    }
}
