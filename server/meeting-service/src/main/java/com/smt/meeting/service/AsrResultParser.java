package com.smt.meeting.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析腾讯 ASR {@code ResTextFormat=0} 的 {@code Result}：
 * 每行 {@code [开始,结束,说话人]  文本}，与旧版体验页导出的时间戳划分一致。
 *
 * <p>ASR 返回值理论上可信，但供应商字段仍按不可信输入处理：时间越界/倒序/空文本的行跳过，
 * 不让一条异常行拖垮整场转写。</p>
 */
public final class AsrResultParser {

    private static final long MAX_TIMESTAMP_MS = 86_400_000L;
    private static final Pattern LINE = Pattern.compile(
            "^\\[(?:(\\d{1,6}):)?(\\d{1,6}):(\\d{1,2}(?:\\.\\d{1,3})?),"
                    + "(?:(\\d{1,6}):)?(\\d{1,6}):(\\d{1,2}(?:\\.\\d{1,3})?),(\\d{1,32})\\]\\s*(.*)$");

    private AsrResultParser() {
    }

    public static List<AsrClient.Sentence> parse(String result) {
        List<AsrClient.Sentence> sentences = new ArrayList<>();
        if (result == null || result.isBlank()) {
            return sentences;
        }
        for (String raw : result.replace("﻿", "").split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            Matcher matcher = LINE.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            Long start = parseTime(matcher.group(1), matcher.group(2), matcher.group(3));
            Long end = parseTime(matcher.group(4), matcher.group(5), matcher.group(6));
            String text = matcher.group(8).strip();
            if (start == null || end == null || end < start || text.isEmpty()) {
                continue;
            }
            sentences.add(new AsrClient.Sentence(
                    text, start, end, matcher.group(7)));
        }
        return sentences;
    }

    private static Long parseTime(String hours, String minutes, String seconds) {
        try {
            long h = hours == null ? 0 : Long.parseLong(hours);
            long m = Long.parseLong(minutes);
            double s = Double.parseDouble(seconds);
            if ((hours != null && m > 59) || s >= 60) {
                return null;
            }
            long millis = Math.addExact(
                    Math.addExact(
                            Math.multiplyExact(h, 3_600_000L),
                            Math.multiplyExact(m, 60_000L)),
                    Math.round(s * 1_000));
            return millis > MAX_TIMESTAMP_MS ? null : millis;
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }
}
