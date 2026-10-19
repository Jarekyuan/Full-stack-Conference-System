-- M6.7 腾讯 ASR 自动转写：给已有数据库补 asr_* 字段
-- 新库直接使用 db/01-schema.sql；已有库执行一次本文件。
ALTER TABLE meeting_minutes
    ADD COLUMN asr_status      TINYINT      NOT NULL DEFAULT 0
        COMMENT '0 未转写 / 10 待识别 / 20 识别中 / 30 成功 / 40 失败' AFTER ai_finished_at,
    ADD COLUMN asr_task_id     VARCHAR(64)  DEFAULT NULL
        COMMENT '腾讯 ASR TaskId' AFTER asr_status,
    ADD COLUMN asr_error       VARCHAR(500) DEFAULT NULL
        COMMENT '语音识别失败原因（用户可读）' AFTER asr_task_id,
    ADD COLUMN asr_attempts    INT          NOT NULL DEFAULT 0
        COMMENT '语音识别已尝试次数' AFTER asr_error,
    ADD COLUMN asr_started_at  DATETIME     DEFAULT NULL
        COMMENT '本次提交 ASR 的时间（run token）' AFTER asr_attempts,
    ADD COLUMN asr_finished_at DATETIME     DEFAULT NULL
        COMMENT '本次识别结束时间' AFTER asr_started_at,
    ADD KEY idx_asr_status (asr_status, updated_at);
