package com.smt.meeting.service;

import com.smt.meeting.entity.MinutesSpeaker;
import com.smt.meeting.entity.TranscriptSegment;
import com.smt.meeting.vo.MinutesInsightsVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TranscriptInsightsTest {

    private final MinutesInsightsService service = new MinutesInsightsService();

    private MinutesSpeaker speaker(String key, String name) {
        MinutesSpeaker s = new MinutesSpeaker();
        s.setSpeakerKey(key);
        s.setDisplayName(name);
        s.setColor("#7D8CFF");
        return s;
    }

    private TranscriptSegment segment(String speakerKey, long start, long end) {
        TranscriptSegment s = new TranscriptSegment();
        s.setSpeakerKey(speakerKey);
        s.setStartMs(start);
        s.setEndMs(end);
        return s;
    }

    @Test
    void aggregatesSpeakingTimeAndRatios() {
        List<MinutesSpeaker> speakers = List.of(speaker("0", "张三"), speaker("1", "李四"));
        List<TranscriptSegment> segments = List.of(
                segment("0", 0, 10_000),
                segment("1", 10_000, 15_000),
                segment("0", 15_000, 25_000));

        MinutesInsightsVO result = service.compute(speakers, segments, 30_000);

        assertThat(result.getDurationMs()).isEqualTo(30_000);
        assertThat(result.getSpeakingMs()).isEqualTo(25_000);
        assertThat(result.getSpeechCoverage()).isCloseTo(25.0 / 30.0, within(0.0001));
        assertThat(result.getTotalSegments()).isEqualTo(3);
        assertThat(result.getSpeakers()).hasSize(2);
        assertThat(result.getSpeakers().get(0).getSpeakingMs()).isEqualTo(20_000);
        assertThat(result.getSpeakers().get(0).getTalkRatio()).isCloseTo(0.8, within(0.0001));
        assertThat(result.getSpeakers().get(0).getSegmentCount()).isEqualTo(2);
        assertThat(result.getSpeakers().get(0).getLongestSegmentMs()).isEqualTo(10_000);
        assertThat(result.getSpeakers().get(1).getTalkRatio()).isCloseTo(0.2, within(0.0001));
    }

    @Test
    void missingDurationFallsBackToLastSegmentEnd() {
        MinutesInsightsVO result = service.compute(
                List.of(speaker("0", "张三")),
                List.of(segment("0", 0, 12_000)),
                0);
        assertThat(result.getDurationMs()).isEqualTo(12_000);
        assertThat(result.getSpeechCoverage()).isEqualTo(1.0);
    }

    @Test
    void emptySegmentsProduceZeros() {
        MinutesInsightsVO result = service.compute(List.of(), List.of(), 0);
        assertThat(result.getDurationMs()).isZero();
        assertThat(result.getSpeakingMs()).isZero();
        assertThat(result.getSpeechCoverage()).isZero();
        assertThat(result.getTotalSegments()).isZero();
        assertThat(result.getSpeakers()).isEmpty();
    }

    @Test
    void overlappingSegmentsAreClippedAtOneHundredPercent() {
        MinutesInsightsVO result = service.compute(
                List.of(speaker("0", "张三")),
                List.of(segment("0", 0, 30_000), segment("0", 0, 30_000)),
                30_000);
        assertThat(result.getSpeakingMs()).isEqualTo(60_000);
        assertThat(result.getSpeechCoverage()).isEqualTo(1.0);
    }

    @Test
    void unknownSpeakerGetsFallbackDisplayName() {
        MinutesInsightsVO result = service.compute(
                List.of(),
                List.of(segment("9", 0, 1_000)),
                1_000);
        assertThat(result.getSpeakers()).hasSize(1);
        assertThat(result.getSpeakers().get(0).getDisplayName()).isEqualTo("说话人 9");
        assertThat(result.getSpeakers().get(0).getColor()).isEqualTo("#7D8CFF");
    }

    @Test
    void declaredSpeakerWithoutSegmentsKeepsZeroStats() {
        MinutesInsightsVO r = service.compute(List.of(speaker("0", "张三")), List.of(), 0);
        assertThat(r.getSpeakers()).hasSize(1);
        assertThat(r.getSpeakers().get(0).getSpeakingMs()).isZero();
        assertThat(r.getSpeakers().get(0).getTalkRatio()).isZero();
        assertThat(r.getSpeakers().get(0).getTalkRatio()).isNotNaN();
        assertThat(r.getSpeakers().get(0).getSegmentCount()).isZero();
        assertThat(r.getSpeakers().get(0).getLongestSegmentMs()).isZero();
    }

    @Test
    void invertedSegmentIsClampedToZero() {
        MinutesInsightsVO r = service.compute(List.of(speaker("0", "张三")),
                List.of(segment("0", 10_000, 5_000)), 0);
        assertThat(r.getSpeakingMs()).isZero();
        assertThat(r.getSpeakers().get(0).getLongestSegmentMs()).isZero();
        assertThat(r.getSpeakers().get(0).getSegmentCount()).isEqualTo(1);
        assertThat(r.getSpeechCoverage()).isZero();
    }

    @Test
    void providedDurationShorterThanLastEndIsExpanded() {
        MinutesInsightsVO r = service.compute(List.of(speaker("0", "张三")),
                List.of(segment("0", 0, 12_000)), 5_000);
        assertThat(r.getDurationMs()).isEqualTo(12_000);
        assertThat(r.getSpeechCoverage()).isEqualTo(1.0);
    }
}
