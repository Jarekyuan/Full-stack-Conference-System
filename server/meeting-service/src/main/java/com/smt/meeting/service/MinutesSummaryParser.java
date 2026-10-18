package com.smt.meeting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.vo.MinutesSummaryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class MinutesSummaryParser {

    private static final Set<String> PRIORITIES = Set.of("HIGH", "MEDIUM", "LOW");

    private final ObjectMapper objectMapper;

    /** 读取历史 summary：脏数据不阻断详情，只返回 null。 */
    public MinutesSummaryVO parseOrNull(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, MinutesSummaryVO.class);
        } catch (Exception e) {
            log.warn("会议纪要 summary_json 解析失败，按空处理", e);
            return null;
        }
    }

    /** AI 生成结果必须通过校验，否则按生成失败处理。 */
    public MinutesSummaryVO parseAndValidate(String json) {
        MinutesSummaryVO summary;
        try {
            summary = objectMapper.readValue(json, MinutesSummaryVO.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.AI_GENERATION_FAILED.getCode(),
                    "AI 返回的不是合法 JSON");
        }
        if (summary.getSummary() == null || summary.getSummary().isBlank()) {
            throw new BusinessException(ErrorCode.AI_GENERATION_FAILED.getCode(),
                    "AI 纪要缺少摘要");
        }
        if (summary.getKeyPoints() == null || summary.getDecisions() == null
                || summary.getActionItems() == null || summary.getRisks() == null) {
            throw new BusinessException(ErrorCode.AI_GENERATION_FAILED.getCode(),
                    "AI 纪要字段不完整");
        }
        for (MinutesSummaryVO.ActionItemVO item : summary.getActionItems()) {
            if (item.getTask() == null || item.getTask().isBlank()) {
                throw new BusinessException(ErrorCode.AI_GENERATION_FAILED.getCode(),
                        "AI 待办缺少任务内容");
            }
            if (item.getPriority() != null && !PRIORITIES.contains(item.getPriority())) {
                throw new BusinessException(ErrorCode.AI_GENERATION_FAILED.getCode(),
                        "AI 待办优先级不合法");
            }
            if (item.getDueDate() != null && !item.getDueDate().isBlank()) {
                try {
                    LocalDate.parse(item.getDueDate());
                } catch (Exception e) {
                    throw new BusinessException(ErrorCode.AI_GENERATION_FAILED.getCode(),
                            "AI 待办截止日期不合法");
                }
            }
        }
        return summary;
    }

    /** 便于单测构造：不依赖 Spring。 */
    public static MinutesSummaryParser withObjectMapper(ObjectMapper objectMapper) {
        return new MinutesSummaryParser(objectMapper);
    }
}
