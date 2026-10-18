package com.smt.meeting.service;

import com.smt.meeting.entity.MinutesSpeaker;
import com.smt.meeting.entity.TranscriptSegment;
import com.smt.meeting.vo.MinutesInsightsVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MinutesInsightsService {

    private static final String FALLBACK_COLOR = "#7D8CFF";

    public MinutesInsightsVO compute(List<MinutesSpeaker> speakers,
                                     List<TranscriptSegment> segments,
                                     long durationMs) {
        Map<String, SpeakerAgg> aggs = new LinkedHashMap<>();
        for (MinutesSpeaker speaker : speakers) {
            aggs.put(speaker.getSpeakerKey(), new SpeakerAgg(
                    speaker.getSpeakerKey(),
                    speaker.getDisplayName(),
                    speaker.getColor()));
        }

        long speakingMs = 0;
        long lastEndMs = 0;
        for (TranscriptSegment segment : segments) {
            long current = Math.max(0, segment.getEndMs() - segment.getStartMs());
            speakingMs += current;
            lastEndMs = Math.max(lastEndMs, segment.getEndMs());
            SpeakerAgg agg = aggs.computeIfAbsent(segment.getSpeakerKey(),
                    key -> new SpeakerAgg(key, "说话人 " + key, FALLBACK_COLOR));
            agg.speakingMs += current;
            agg.segmentCount++;
            agg.longestSegmentMs = Math.max(agg.longestSegmentMs, current);
        }

        long effectiveDuration = durationMs > 0 ? Math.max(durationMs, lastEndMs) : lastEndMs;
        MinutesInsightsVO vo = new MinutesInsightsVO();
        vo.setDurationMs(effectiveDuration);
        vo.setSpeakingMs(speakingMs);
        vo.setSpeechCoverage(effectiveDuration == 0
                ? 0.0
                : Math.min(1.0, (double) speakingMs / effectiveDuration));
        vo.setTotalSegments(segments.size());

        List<MinutesInsightsVO.SpeakerInsightVO> speakerVOs = new ArrayList<>();
        for (SpeakerAgg agg : aggs.values()) {
            MinutesInsightsVO.SpeakerInsightVO item = new MinutesInsightsVO.SpeakerInsightVO();
            item.setSpeakerKey(agg.speakerKey);
            item.setDisplayName(agg.displayName);
            item.setColor(agg.color);
            item.setSpeakingMs(agg.speakingMs);
            item.setTalkRatio(speakingMs == 0 ? 0.0 : (double) agg.speakingMs / speakingMs);
            item.setSegmentCount(agg.segmentCount);
            item.setLongestSegmentMs(agg.longestSegmentMs);
            speakerVOs.add(item);
        }
        vo.setSpeakers(speakerVOs);
        return vo;
    }

    private static final class SpeakerAgg {
        private final String speakerKey;
        private final String displayName;
        private final String color;
        private long speakingMs;
        private int segmentCount;
        private long longestSegmentMs;

        private SpeakerAgg(String speakerKey, String displayName, String color) {
            this.speakerKey = speakerKey;
            this.displayName = displayName;
            this.color = color;
        }
    }
}
