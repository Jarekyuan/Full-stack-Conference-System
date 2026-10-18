package com.smt.meeting.service;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.config.MinutesProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TencentAsrParserTest {

    private TranscriptParser parser() {
        MinutesProperties properties = new MinutesProperties();
        properties.getTranscript().setMaxBytes(5L * 1024 * 1024);
        properties.getTranscript().setMaxSegments(20_000);
        return new TranscriptParser(properties);
    }

    private TranscriptParser parserWithTranscript(Consumer<MinutesProperties.Transcript> customizer) {
        MinutesProperties properties = new MinutesProperties();
        customizer.accept(properties.getTranscript());
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
                .satisfies(e -> {
                    BusinessException ex = (BusinessException) e;
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.TRANSCRIPT_FORMAT_INVALID.getCode());
                    assertThat(((Map<?, ?>) ex.getData()).get("line")).isEqualTo(1);
                });
    }

    @Test
    void rejectsOutOfOrderSegments() {
        assertThatThrownBy(() -> parser().parse(
                "[0:2.000,0:3.000,0]  后\n[0:1.000,0:1.500,0]  前"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException ex = (BusinessException) e;
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.TRANSCRIPT_FORMAT_INVALID.getCode());
                    assertThat(((Map<?, ?>) ex.getData()).get("line")).isEqualTo(2);
                });
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

    @Test
    void supportsMinuteBasedLongRecordings() {
        TranscriptParser.ParseResult result = parser().parse(
                "[60:00.000,60:00.500,0]  长录音分钟写法\n"
                        + "[1:00:00.000,1:00:00.500,1]  长录音小时写法");
        assertThat(result.segments()).hasSize(2);
        assertThat(result.segments().get(0).startMs()).isEqualTo(3_600_000);
        assertThat(result.segments().get(1).startMs()).isEqualTo(3_600_000);
        assertThat(result.speakerKeys()).containsExactly("0", "1");
    }

    @Test
    void rejectsMinutesOver59WhenHoursPresent() {
        assertThatThrownBy(() -> parser().parse("[1:60:00.000,1:60:00.500,0]  非法分钟"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.TRANSCRIPT_FORMAT_INVALID.getCode());
    }

    @Test
    void rejectsExcessiveFractionalPrecision() {
        assertThatThrownBy(() -> parser().parse("[0:59.9995,0:59.9996,0]  精度过度"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.TRANSCRIPT_FORMAT_INVALID.getCode());
    }

    @Test
    void accepts32DigitSpeakerKey() {
        String key = "12345678901234567890123456789012";
        TranscriptParser.ParseResult result = parser().parse(
                "[0:1.000,0:2.000," + key + "]  长编号");
        assertThat(result.speakerKeys()).containsExactly(key);
    }

    @Test
    void rejects33DigitSpeakerKey() {
        String key = "123456789012345678901234567890123";
        assertThatThrownBy(() -> parser().parse(
                "[0:1.000,0:2.000," + key + "]  超长编号"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException ex = (BusinessException) e;
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.TRANSCRIPT_FORMAT_INVALID.getCode());
                    assertThat(((Map<?, ?>) ex.getData()).get("line")).isEqualTo(1);
                });
    }

    @Test
    void enforcesConfiguredMaxBytesBoundary() {
        String text = "[0:1.000,0:2.000,0]  你好";
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;

        TranscriptParser exact = parserWithTranscript(t -> t.setMaxBytes((long) bytes));
        assertThat(exact.parse(text).segments()).hasSize(1);

        TranscriptParser oneLess = parserWithTranscript(t -> t.setMaxBytes((long) bytes - 1));
        assertThatThrownBy(() -> oneLess.parse(text))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.FILE_TOO_LARGE.getCode());
    }

    @Test
    void enforcesMaxSegmentCharsBoundary() {
        TranscriptParser limited = parserWithTranscript(t -> t.setMaxSegmentChars(3));
        assertThat(limited.parse("[0:1.000,0:2.000,0]  abc").segments().get(0).text())
                .isEqualTo("abc");
        assertThatThrownBy(() -> limited.parse("[0:1.000,0:2.000,0]  abcd"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.TRANSCRIPT_FORMAT_INVALID.getCode());
    }

    @Test
    void enforcesMaxSegmentsExactBoundary() {
        TranscriptParser limited = parserWithTranscript(t -> t.setMaxSegments(2));
        String twoSegments = "[0:1.000,0:2.000,0]  一\n[0:3.000,0:4.000,0]  二";
        assertThat(limited.parse(twoSegments).segments()).hasSize(2);
        assertThatThrownBy(() -> limited.parse(twoSegments + "\n[0:5.000,0:6.000,0]  三"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.TRANSCRIPT_FORMAT_INVALID.getCode());
    }

    @Test
    void allowsDuplicateSegmentStarts() {
        TranscriptParser.ParseResult result = parser().parse(
                "[0:1.000,0:2.000,0]  一\n[0:1.000,0:1.500,1]  二");
        assertThat(result.segments()).hasSize(2);
    }

    @Test
    void keepsSpeakerFirstAppearanceOrder() {
        TranscriptParser.ParseResult result = parser().parse(
                "[0:1.000,0:2.000,2]  甲\n"
                        + "[0:3.000,0:4.000,0]  乙\n"
                        + "[0:5.000,0:6.000,2]  丙");
        assertThat(result.speakerKeys()).containsExactly("2", "0");
    }

    @Test
    void lastEndIsMaximumEndAcrossSegments() {
        TranscriptParser.ParseResult result = parser().parse(
                "[0:0.000,0:5.000,0]  长\n[0:1.000,0:2.000,0]  短");
        assertThat(result.lastEndMs()).isEqualTo(5_000);
    }

    @Test
    void rejectsTimestampsBeyond24Hours() {
        assertThatThrownBy(() -> parser().parse("[24:00:00.001,24:00:01.000,0]  超一天"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException ex = (BusinessException) e;
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.TRANSCRIPT_FORMAT_INVALID.getCode());
                    assertThat(((Map<?, ?>) ex.getData()).get("reason")).isEqualTo("时间超过 24 小时");
                });
    }

    @Test
    void rejectsEmptySegmentTextAfterUnicodeWhitespaceStrip() {
        assertThatThrownBy(() -> parser().parse("[0:1.000,0:2.000,0]　　"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException ex = (BusinessException) e;
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.TRANSCRIPT_FORMAT_INVALID.getCode());
                    assertThat(((Map<?, ?>) ex.getData()).get("line")).isEqualTo(1);
                    assertThat(((Map<?, ?>) ex.getData()).get("reason")).isEqualTo("文本不能为空");
                });
    }
}
