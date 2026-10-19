package com.smt.meeting.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AsrResultParserTest {

    @Test
    void parsesTencentFormatZeroLines() {
        var sentences = AsrResultParser.parse("""
                [0:1.030,0:8.570,0]  你好，
                [0:9.000,0:12.500,1] 世界。
                """);

        assertThat(sentences).hasSize(2);
        assertThat(sentences.get(0)).isEqualTo(
                new AsrClient.Sentence("你好，", 1030, 8570, "0"));
        assertThat(sentences.get(1).speakerKey()).isEqualTo("1");
        assertThat(sentences.get(1).startMs()).isEqualTo(9000);
    }

    @Test
    void supportsHourMinutesAndSkipsMalformedLines() {
        var sentences = AsrResultParser.parse("""
                [1:02:03.000,1:02:04.500,0] 长会议
                不是时间戳行
                [0:5.000,0:4.000,0] 倒序
                [0:6.000,0:7.000,0]
                """);

        assertThat(sentences).singleElement().satisfies(sentence -> {
            assertThat(sentence.text()).isEqualTo("长会议");
            assertThat(sentence.startMs()).isEqualTo(3_723_000);
            assertThat(sentence.endMs()).isEqualTo(3_724_500);
        });
    }

    @Test
    void blankResultReturnsEmpty() {
        assertThat(AsrResultParser.parse(null)).isEmpty();
        assertThat(AsrResultParser.parse("  ")).isEmpty();
    }
}
