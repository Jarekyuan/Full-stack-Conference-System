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
}
