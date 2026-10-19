package com.smt.meeting.service;

import java.util.List;

/**
 * 录音文件识别客户端抽象：提交异步任务 + 查询任务状态。
 * 真实实现 {@link TencentAsrClient} 调腾讯云；{@link MockAsrClient} 用于本地/E2E。
 */
public interface AsrClient {

    /** 提交公网可访问的音频 URL，返回腾讯 TaskId（字符串保存，避免前端/DB 精度问题） */
    String submit(String audioUrl);

    /** 查询任务状态；SUCCESS 时返回句子列表 */
    AsrQueryResult query(String taskId);

    enum Status {
        WAITING,
        PROCESSING,
        SUCCESS,
        FAILED
    }

    record Sentence(String text, long startMs, long endMs, String speakerKey) {
    }

    record AsrQueryResult(Status status, List<Sentence> sentences, long durationMs, String error) {
        public static AsrQueryResult waiting() {
            return new AsrQueryResult(Status.WAITING, List.of(), 0L, null);
        }

        public static AsrQueryResult processing() {
            return new AsrQueryResult(Status.PROCESSING, List.of(), 0L, null);
        }

        public static AsrQueryResult failed(String error) {
            return new AsrQueryResult(Status.FAILED, List.of(), 0L, error);
        }
    }
}
