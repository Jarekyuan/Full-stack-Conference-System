package com.smt.meeting.service;

import com.smt.meeting.config.AsrProperties;
import com.tencentcloudapi.asr.v20190614.models.CreateRecTaskRequest;
import com.tencentcloudapi.asr.v20190614.models.CreateRecTaskResponse;
import com.tencentcloudapi.asr.v20190614.models.DescribeTaskStatusRequest;
import com.tencentcloudapi.asr.v20190614.models.SentenceDetail;
import com.tencentcloudapi.asr.v20190614.models.TaskStatus;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 腾讯云录音文件识别（异步）客户端。
 *
 * <p>安全约束：不记录 SecretId/SecretKey、预签名 URL 或识别正文；供应商异常只记录
 * 错误码/类型与固定文案，不把原始响应写进日志或 {@code asr_error}。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class TencentAsrClient implements AsrClient {

    /** 避免和本项目的 AsrClient 接口重名，SDK 客户端使用全限定名 */
    private final com.tencentcloudapi.asr.v20190614.AsrClient sdk;
    private final AsrProperties properties;

    @Override
    public String submit(String audioUrl) {
        CreateRecTaskRequest request = new CreateRecTaskRequest();
        request.setEngineModelType(properties.getEngineModelType());
        request.setChannelNum(longValue(properties.getChannelNum(), 1L));
        request.setResTextFormat(longValue(properties.getResTextFormat(), 0L));
        request.setSourceType(0L);
        request.setUrl(audioUrl);
        request.setSpeakerDiarization(longValue(properties.getSpeakerDiarization(), 1L));
        request.setSpeakerNumber(longValue(properties.getSpeakerNumber(), 0L));
        request.setFilterPunc(0L);
        request.setConvertNumMode(1L);
        try {
            CreateRecTaskResponse response = sdk.CreateRecTask(request);
            if (response.getData() == null || response.getData().getTaskId() == null) {
                throw new IllegalStateException("ASR 未返回 TaskId");
            }
            return String.valueOf(response.getData().getTaskId());
        } catch (TencentCloudSDKException e) {
            log.warn("[纪要 ASR] 提交失败 code={} requestId={}",
                    e.getErrorCode(), e.getRequestId());
            throw new IllegalStateException("语音识别提交失败", e);
        }
    }

    @Override
    public AsrQueryResult query(String taskId) {
        DescribeTaskStatusRequest request = new DescribeTaskStatusRequest();
        try {
            request.setTaskId(Long.valueOf(taskId));
        } catch (NumberFormatException e) {
            return AsrQueryResult.failed("语音识别任务号无效");
        }
        try {
            TaskStatus data = sdk.DescribeTaskStatus(request).getData();
            if (data == null || data.getStatus() == null) {
                return AsrQueryResult.processing();
            }
            long status = data.getStatus();
            if (status == 0L) {
                return AsrQueryResult.waiting();
            }
            if (status == 1L) {
                return AsrQueryResult.processing();
            }
            if (status == 3L) {
                return AsrQueryResult.failed("语音识别失败，请重试");
            }
            if (status != 2L) {
                return AsrQueryResult.processing();
            }
            // ResTextFormat=0 的 Result 是旧版体验页的 [开始,结束,说话人] 行，
            // 比 ResultDetail 的逐句切分更接近用户习惯；为空时再回退到 structured detail。
            List<Sentence> sentences = AsrResultParser.parse(data.getResult());
            if (sentences.isEmpty()) {
                sentences = mapSentences(data.getResultDetail());
            }
            long durationMs = data.getAudioDuration() == null
                    ? 0L : Math.round(data.getAudioDuration() * 1000);
            return new AsrQueryResult(Status.SUCCESS, sentences, durationMs, null);
        } catch (TencentCloudSDKException e) {
            log.warn("[纪要 ASR] 查询失败 code={} requestId={}",
                    e.getErrorCode(), e.getRequestId());
            // 网络/供应商抖动交给 worker 重试，不把原始错误暴露给用户
            return AsrQueryResult.processing();
        }
    }

    private List<Sentence> mapSentences(SentenceDetail[] details) {
        List<Sentence> sentences = new ArrayList<>();
        if (details == null) {
            return sentences;
        }
        for (SentenceDetail detail : details) {
            if (detail == null || detail.getFinalSentence() == null) {
                continue;
            }
            String text = detail.getFinalSentence().strip();
            if (text.isEmpty()) {
                continue;
            }
            long start = detail.getStartMs() == null ? 0L : detail.getStartMs();
            long end = detail.getEndMs() == null ? start : detail.getEndMs();
            String speakerKey = detail.getSpeakerId() == null
                    ? "0" : String.valueOf(detail.getSpeakerId());
            sentences.add(new Sentence(text, start, Math.max(start, end), speakerKey));
        }
        return sentences;
    }

    private long longValue(Integer value, long fallback) {
        return value == null ? fallback : value;
    }
}
