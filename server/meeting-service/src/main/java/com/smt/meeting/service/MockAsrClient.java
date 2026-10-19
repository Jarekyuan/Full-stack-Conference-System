package com.smt.meeting.service;

import com.smt.meeting.config.AsrProperties;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 本地/E2E 用的 ASR 假实现：提交即返回一个任务号，查询直接成功。
 *
 * <p>默认返回两句带两个说话人的文本，便于验证现有联动；把
 * {@code minutes.asr.mock-segments} 设为 300 可用于虚拟滚动 E2E。</p>
 */
@RequiredArgsConstructor
public class MockAsrClient implements AsrClient {

    private final AsrProperties properties;

    @Override
    public String submit(String audioUrl) {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public AsrQueryResult query(String taskId) {
        int count = properties.getMockSegments() == null
                ? 2 : Math.max(1, properties.getMockSegments());
        List<Sentence> sentences = new ArrayList<>(count);
        long cursor = 0L;
        for (int i = 0; i < count; i++) {
            long start = cursor;
            long end = start + 1000L;
            cursor = end;
            String text;
            if (count == 2 && i == 0) {
                text = "你好，";
            } else if (count == 2) {
                text = "世界。";
            } else {
                text = "第 " + (i + 1) + " 段会议转写内容。";
            }
            String speakerKey = count == 2 ? String.valueOf(i) : String.valueOf(i % 2);
            sentences.add(new Sentence(text, start, end, speakerKey));
        }
        return new AsrQueryResult(Status.SUCCESS, sentences, cursor, null);
    }
}
