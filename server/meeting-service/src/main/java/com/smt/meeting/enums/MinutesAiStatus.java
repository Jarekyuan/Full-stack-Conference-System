package com.smt.meeting.enums;

import lombok.Getter;

@Getter
public enum MinutesAiStatus {

    NOT_GENERATED(0),
    PENDING(10),
    RUNNING(20),
    SUCCESS(30),
    FAILED(40);

    private final int code;

    MinutesAiStatus(int code) {
        this.code = code;
    }

    public static MinutesAiStatus of(int code) {
        for (MinutesAiStatus value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知纪要 AI 状态: " + code);
    }

    /**
     * 未知状态回退为原始数字，避免错误处理路径本身因未知 DB 值再抛异常。
     */
    public static String labelOrRaw(int code) {
        for (MinutesAiStatus value : values()) {
            if (value.code == code) {
                return value.name();
            }
        }
        return String.valueOf(code);
    }
}
