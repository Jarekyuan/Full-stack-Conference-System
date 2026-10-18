package com.smt.meeting.service;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.config.MinutesProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class TranscriptParser {

    /** 兼容 m:ss.SSS 与 h:mm:ss.SSS */
    private static final Pattern LINE = Pattern.compile(
            "^\\[(?:(\\d+):)?(\\d+):(\\d+(?:\\.\\d+)?),"
                    + "(?:(\\d+):)?(\\d+):(\\d+(?:\\.\\d+)?),(\\d+)\\]\\s*(.+)$");

    private final MinutesProperties properties;

    public ParseResult parse(String text) {
        if (text == null || text.isBlank()) {
            throw new BusinessException(ErrorCode.TRANSCRIPT_EMPTY);
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > properties.getTranscript().getMaxBytes()) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE.getCode(), "转写文本超过 5MiB");
        }

        List<ParsedSegment> segments = new ArrayList<>();
        Set<String> speakerKeys = new LinkedHashSet<>();
        long previousStart = -1;
        long lastEnd = 0;

        String[] lines = text.replace("﻿", "").split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            int lineNo = i + 1;
            Matcher matcher = LINE.matcher(line);
            if (!matcher.matches()) {
                throw formatError(lineNo, "应为 [开始,结束,说话人] 文本");
            }

            long startMs = parseTime(matcher.group(1), matcher.group(2), matcher.group(3), lineNo);
            long endMs = parseTime(matcher.group(4), matcher.group(5), matcher.group(6), lineNo);
            String speakerKey = matcher.group(7);
            String content = matcher.group(8).trim();

            if (startMs >= endMs) {
                throw formatError(lineNo, "开始时间必须早于结束时间");
            }
            if (startMs < previousStart) {
                throw formatError(lineNo, "分段开始时间不能倒序");
            }
            if (content.length() > properties.getTranscript().getMaxSegmentChars()) {
                throw formatError(lineNo, "单段文本超过 "
                        + properties.getTranscript().getMaxSegmentChars() + " 字符");
            }
            if (segments.size() >= properties.getTranscript().getMaxSegments()) {
                throw formatError(lineNo, "分段数超过上限 "
                        + properties.getTranscript().getMaxSegments());
            }

            segments.add(new ParsedSegment(segments.size(), speakerKey, startMs, endMs, content));
            speakerKeys.add(speakerKey);
            previousStart = startMs;
            lastEnd = Math.max(lastEnd, endMs);
        }

        if (segments.isEmpty()) {
            throw new BusinessException(ErrorCode.TRANSCRIPT_EMPTY);
        }
        return new ParseResult(segments, List.copyOf(speakerKeys), lastEnd);
    }

    private long parseTime(String hours, String minutes, String seconds, int lineNo) {
        long h = hours == null ? 0 : Long.parseLong(hours);
        long m = Long.parseLong(minutes);
        double s = Double.parseDouble(seconds);
        if (m > 59 || s >= 60) {
            throw formatError(lineNo, "时间格式不正确");
        }
        return h * 3_600_000 + m * 60_000 + Math.round(s * 1_000);
    }

    private BusinessException formatError(int line, String reason) {
        return new BusinessException(ErrorCode.TRANSCRIPT_FORMAT_INVALID,
                Map.of("line", line, "reason", reason));
    }

    public record ParsedSegment(int seq, String speakerKey, long startMs, long endMs, String text) {
    }

    public record ParseResult(List<ParsedSegment> segments, List<String> speakerKeys, long lastEndMs) {
    }
}
