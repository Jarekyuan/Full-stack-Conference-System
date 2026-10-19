package com.smt.meeting.enums;

/**
 * 腾讯 ASR 转写状态：0 未转写 / 10 待识别 / 20 识别中 / 30 成功 / 40 失败。
 * 与 AI 纪要的 ai_status 独立：先有转写，才允许生成纪要。
 */
public final class MinutesAsrStatus {

    public static final int NOT_STARTED = 0;
    public static final int PENDING = 10;
    public static final int RUNNING = 20;
    public static final int SUCCESS = 30;
    public static final int FAILED = 40;

    private MinutesAsrStatus() {
    }
}
