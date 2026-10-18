package com.smt.meeting.vo;

import lombok.Data;

import java.util.List;

@Data
public class MinutesInsightsVO {

    private Long durationMs;
    private Long speakingMs;
    private Double speechCoverage;
    private Integer totalSegments;
    private List<SpeakerInsightVO> speakers;

    @Data
    public static class SpeakerInsightVO {
        private String speakerKey;
        private String displayName;
        private String color;
        private Long speakingMs;
        private Double talkRatio;
        private Integer segmentCount;
        private Long longestSegmentMs;
    }
}
