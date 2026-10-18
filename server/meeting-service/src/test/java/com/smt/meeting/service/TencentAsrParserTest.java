package com.smt.meeting.service;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.config.MinutesProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TencentAsrParserTest {

    private TranscriptParser parser() {
        MinutesProperties properties = new MinutesProperties();
        properties.getTranscript().setMaxBytes(5L * 1024 * 1024);
        properties.getTranscript().setMaxSegments(20_000);
        return new TranscriptParser(properties);
    }

    @Test
    void parsesStandardLinesWithSpeakers() {
        String text = """
                [0:1.030,0:8.570,0]  恩格斯论述了雅典国家。
                [0:9.350,0:12.030,1]  演变的历程和主要的特点。
                """;

        TranscriptParser.ParseResult result = parser().parse(text);

        assertThat(result.segments()).hasSize(2);
        assertThat(result.speakerKeys()).containsExactly("0", "1");
        assertThat(result.lastEndMs()).isEqualTo(12_030);
        assertThat(result.segments().get(0).startMs()).isEqualTo(1_030);
        assertThat(result.segments().get(0).endMs()).isEqualTo(8_570);
        assertThat(result.segments().get(0).text()).isEqualTo("恩格斯论述了雅典国家。");
        assertThat(result.segments().get(0).seq()).isZero();
    }

    @Test
    void parsesHourMinuteSecondFormat() {
        String text = "[1:02:03.400,1:02:05.000,2]  跨小时分段。";
        TranscriptParser.ParseResult result = parser().parse(text);
        assertThat(result.segments().get(0).startMs()).isEqualTo(3_723_400);
        assertThat(result.segments().get(0).endMs()).isEqualTo(3_725_000);
    }

    @Test
    void ignoresBlankLinesAndBom() {
        TranscriptParser.ParseResult result = parser().parse("﻿\n[0:1.000,0:2.000,0]  你好\n\n");
        assertThat(result.segments()).hasSize(1);
        assertThat(result.segments().get(0).text()).isEqualTo("你好");
    }

    @Test
    void rejectsMalformedLineWithLineNumber() {
        assertThatThrownBy(() -> parser().parse("[0:1.000,0:2.000,0]  正常\n这不是一行转写"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException ex = (BusinessException) e;
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.TRANSCRIPT_FORMAT_INVALID.getCode());
                    assertThat((Map<String, Object>) ex.getData()).containsEntry("line", 2);
                });
    }

    @Test
    void rejectsStartNotBeforeEnd() {
        assertThatThrownBy(() -> parser().parse("[0:2.000,0:2.000,0]  零时长"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.TRANSCRIPT_FORMAT_INVALID.getCode());
    }

    @Test
    void rejectsOutOfOrderSegments() {
        assertThatThrownBy(() -> parser().parse(
                "[0:2.000,0:3.000,0]  后\n[0:1.000,0:1.500,0]  前"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.TRANSCRIPT_FORMAT_INVALID.getCode());
    }

    @Test
    void rejectsEmptyContent() {
        assertThatThrownBy(() -> parser().parse("\n\n"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.TRANSCRIPT_EMPTY.getCode());
    }

    @Test
    void rejectsTooManySegments() {
        MinutesProperties properties = new MinutesProperties();
        properties.getTranscript().setMaxSegments(1);
        TranscriptParser limited = new TranscriptParser(properties);
        assertThatThrownBy(() -> limited.parse(
                "[0:1.000,0:2.000,0]  一\n[0:3.000,0:4.000,0]  二"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.TRANSCRIPT_FORMAT_INVALID.getCode());
    }
}
