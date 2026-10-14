package com.smt.common.exception;

import com.smt.common.result.ErrorCode;
import lombok.Getter;

/**
 * 业务异常：携带错误码与可选结构化数据（如时段冲突详情），
 * 由 {@link GlobalExceptionHandler} 统一转为 Result 响应
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;
    private final Object data;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMsg());
        this.code = errorCode.getCode();
        this.data = null;
    }

    /** 携带结构化数据（如 data.conflicts 冲突详情） */
    public BusinessException(ErrorCode errorCode, Object data) {
        super(errorCode.getMsg());
        this.code = errorCode.getCode();
        this.data = data;
    }

    public BusinessException(int code, String msg) {
        super(msg);
        this.code = code;
        this.data = null;
    }
}
