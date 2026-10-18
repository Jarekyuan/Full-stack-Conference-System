package com.smt.meeting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.vo.MinutesSummaryVO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI 纪要 JSON 校验：宽松读取（parseOrNull）与严格校验（parseAndValidate）的边界。
 */
class MinutesSummaryParserTest {

    private static final String VALID = """
            {
              "summary": "讨论了发布计划",
              "keyPoints": ["版本范围"],
              "decisions": [{"content": "本周五发布", "context": "评审"}],
              "actionItems": [{"task": "补回归", "owner": "张三",
                               "dueDate": "2026-10-20", "priority": "HIGH"}],
              "risks": ["测试时间不足"]
            }
            """;

    private final MinutesSummaryParser parser =
            MinutesSummaryParser.withObjectMapper(new ObjectMapper());

    private void assertAiFailure(String json) {
        assertThatThrownBy(() -> parser.parseAndValidate(json))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.AI_GENERATION_FAILED.getCode());
    }

    @Test
    void parsesValidSummary() {
        MinutesSummaryVO summary = parser.parseAndValidate(VALID);

        assertThat(summary.getSummary()).isEqualTo("讨论了发布计划");
        assertThat(summary.getKeyPoints()).containsExactly("版本范围");
        assertThat(summary.getDecisions()).singleElement().satisfies(decision ->
                assertThat(decision.getContent()).isEqualTo("本周五发布"));
        assertThat(summary.getActionItems()).singleElement().satisfies(item -> {
            assertThat(item.getTask()).isEqualTo("补回归");
            assertThat(item.getOwner()).isEqualTo("张三");
            assertThat(item.getDueDate()).isEqualTo("2026-10-20");
            assertThat(item.getPriority()).isEqualTo("HIGH");
        });
        assertThat(summary.getRisks()).containsExactly("测试时间不足");
    }

    @Test
    void rejectsJsonNull() {
        assertAiFailure("null");
    }

    @Test
    void rejectsNullActionItemElement() {
        assertAiFailure("""
                {"summary":"x","keyPoints":[],"decisions":[],
                 "actionItems":[null],"risks":[]}
                """);
    }

    @Test
    void rejectsNullDecisionElement() {
        assertAiFailure("""
                {"summary":"x","keyPoints":[],"decisions":[null],
                 "actionItems":[],"risks":[]}
                """);
    }

    @Test
    void rejectsNullKeyPointElement() {
        assertAiFailure("""
                {"summary":"x","keyPoints":[null],"decisions":[],
                 "actionItems":[],"risks":[]}
                """);
    }

    @Test
    void rejectsMissingFields() {
        assertAiFailure("""
                {"summary":"x","keyPoints":[]}
                """);
    }

    @Test
    void rejectsInvalidPriority() {
        assertAiFailure("""
                {"summary":"x","keyPoints":[],"decisions":[],"risks":[],
                 "actionItems":[{"task":"t","priority":"URGENT"}]}
                """);
    }

    @Test
    void rejectsInvalidDueDate() {
        assertAiFailure("""
                {"summary":"x","keyPoints":[],"decisions":[],"risks":[],
                 "actionItems":[{"task":"t","dueDate":"2026-13-40"}]}
                """);
    }

    @Test
    void parseOrNullSwallowsMalformedJson() {
        assertThat(parser.parseOrNull("{not-json")).isNull();
        assertThat(parser.parseOrNull("  ")).isNull();
        assertThat(parser.parseOrNull(null)).isNull();
    }
}
