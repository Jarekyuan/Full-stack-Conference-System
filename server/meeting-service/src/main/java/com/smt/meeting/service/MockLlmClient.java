package com.smt.meeting.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地/联调用的假 LLM：不调用外部服务，直接从转写文本做抽取式纪要。
 *
 * <p>早期版本返回固定的“产品排期/压测”示例，和用户实际上传的内容完全无关，
 * 很容易被误认为 AI 生成错误。现在按转写行提取摘要、要点、决策、待办与风险，
 * 让 mock 模式下的演示结果至少与上传内容一致；真正的大模型生成仍需配置
 * {@code MINUTES_AI_PROVIDER=deepseek} 与 {@code DEEPSEEK_API_KEY}。</p>
 *
 * <p>userPrompt 含 {@code MOCK_FAIL} 时主动抛错，用于验证重试与失败状态迁移。</p>
 */
public class MockLlmClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TRANSCRIPT_LINE =
            Pattern.compile("^\\[(\\d{2}:\\d{2}:\\d{2})\\]\\s*([^：:]+)[：:]\\s*(.*)$");
    private static final Pattern DECISION =
            Pattern.compile("决定|确定|确认|采用|同意|通过|敲定|选定|方案");
    private static final Pattern ACTION =
            Pattern.compile("负责|跟进|完成|补充|安排|下周|明天|截止|待办|提交|产出|落实|推进");
    private static final Pattern RISK =
            Pattern.compile("风险|问题|阻塞|延期|分歧|担心|尚未|未联调|不确定|困难");
    private static final String CHUNK_PROMPT_MARKER = "5-10 条要点";
    private static final int MAX_ITEMS = 5;
    private static final int MAX_DECISIONS = 3;
    private static final int MAX_ACTION_ITEMS = 3;
    private static final int MAX_RISKS = 3;
    private static final int MAX_TEXT_CHARS = 80;

    @Override
    public String completeJson(String systemPrompt, String userPrompt) {
        if (userPrompt != null && userPrompt.contains("MOCK_FAIL")) {
            throw new IllegalStateException("mock ai failure");
        }
        // 分块阶段要求纯文本要点，最终阶段才要求 JSON。
        if (systemPrompt != null && systemPrompt.contains(CHUNK_PROMPT_MARKER)) {
            return chunkNotes(userPrompt);
        }
        try {
            return MAPPER.writeValueAsString(buildSummary(userPrompt));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("mock ai serialize failure", e);
        }
    }

    private String chunkNotes(String prompt) {
        List<Line> lines = extractLines(prompt);
        if (lines.isEmpty()) {
            return "- 未识别到可用的语音转写内容";
        }
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (Line line : lines) {
            if (count >= 10) {
                break;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append("- ");
            if (line.speaker() != null) {
                builder.append(line.speaker()).append("：");
            }
            builder.append(shorten(line.text()));
            count++;
        }
        return builder.toString();
    }

    private Map<String, Object> buildSummary(String prompt) {
        List<Line> lines = extractLines(prompt);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("summary", summaryText(lines));
        summary.put("keyPoints", keyPoints(lines));
        summary.put("decisions", decisions(lines));
        summary.put("actionItems", actionItems(lines));
        summary.put("risks", risks(lines));
        return summary;
    }

    private String summaryText(List<Line> lines) {
        if (lines.isEmpty()) {
            return "未识别到可用的语音转写内容。";
        }
        StringBuilder builder = new StringBuilder("本次会议主要讨论了：");
        int used = 0;
        for (Line line : lines) {
            if (line.text().isBlank()) {
                continue;
            }
            if (used > 0) {
                builder.append("；");
            }
            builder.append(shorten(line.text()));
            used++;
            if (used >= 2) {
                break;
            }
        }
        if (used == 0) {
            return "未识别到可用的语音转写内容。";
        }
        if (!endsWithPunctuation(builder)) {
            builder.append('。');
        }
        return builder.toString();
    }

    private List<String> keyPoints(List<Line> lines) {
        Set<String> points = new LinkedHashSet<>();
        for (Line line : lines) {
            if (line.text().isBlank()) {
                continue;
            }
            points.add(shorten(line.text()));
            if (points.size() >= MAX_ITEMS) {
                break;
            }
        }
        return new ArrayList<>(points);
    }

    private List<Map<String, Object>> decisions(List<Line> lines) {
        List<Map<String, Object>> decisions = new ArrayList<>();
        for (Line line : lines) {
            if (decisions.size() >= MAX_DECISIONS) {
                break;
            }
            if (!DECISION.matcher(line.text()).find()) {
                continue;
            }
            Map<String, Object> decision = new LinkedHashMap<>();
            decision.put("content", shorten(line.text()));
            decision.put("context", line.speaker() == null
                    ? "来自语音转写" : line.speaker() + " 在语音转写中提出");
            decisions.add(decision);
        }
        return decisions;
    }

    private List<Map<String, Object>> actionItems(List<Line> lines) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Line line : lines) {
            if (items.size() >= MAX_ACTION_ITEMS) {
                break;
            }
            if (!ACTION.matcher(line.text()).find()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("task", shorten(line.text()));
            item.put("owner", ownerOf(line.speaker()));
            item.put("dueDate", null);
            item.put("priority", "MEDIUM");
            items.add(item);
        }
        return items;
    }

    private List<String> risks(List<Line> lines) {
        List<String> risks = new ArrayList<>();
        for (Line line : lines) {
            if (risks.size() >= MAX_RISKS) {
                break;
            }
            if (RISK.matcher(line.text()).find()) {
                risks.add(shorten(line.text()));
            }
        }
        return risks;
    }

    /**
     * 从完整 prompt / 分块 prompt / 合并 prompt 中提取有效内容行。
     * 转写行解析出说话人；分块后的 {@code - 张三：...} 或普通文本行保留文本。
     */
    private List<Line> extractLines(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return List.of();
        }
        String source = prompt;
        int start = prompt.indexOf(MinutesPromptBuilder.TRANSCRIPT_START);
        int end = prompt.indexOf(MinutesPromptBuilder.TRANSCRIPT_END);
        if (start >= 0 && end > start) {
            source = prompt.substring(start + MinutesPromptBuilder.TRANSCRIPT_START.length(), end);
        }
        List<Line> lines = new ArrayList<>();
        for (String raw : source.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()
                    || line.startsWith("---")
                    || line.startsWith("以下是")
                    || line.startsWith("会议转写")) {
                continue;
            }
            if (line.startsWith("- ")) {
                line = line.substring(2).strip();
            }
            if (line.isEmpty()) {
                continue;
            }
            Matcher matcher = TRANSCRIPT_LINE.matcher(line);
            if (matcher.matches()) {
                String text = matcher.group(3).strip();
                if (!text.isEmpty()) {
                    lines.add(new Line(matcher.group(2).strip(), text));
                }
            } else {
                lines.add(new Line(null, line));
            }
        }
        return lines;
    }

    /** 说话人只是 ASR 的匿名编号时不要伪造负责人。 */
    private String ownerOf(String speaker) {
        if (speaker == null || speaker.isBlank() || speaker.startsWith("说话人")) {
            return null;
        }
        return speaker;
    }

    private String shorten(String text) {
        String compact = text.replaceAll("\\s+", " ").strip();
        return compact.length() <= MAX_TEXT_CHARS
                ? compact : compact.substring(0, MAX_TEXT_CHARS) + "…";
    }

    private boolean endsWithPunctuation(CharSequence text) {
        if (text.length() == 0) {
            return false;
        }
        char last = text.charAt(text.length() - 1);
        return "。！？!?；;.".indexOf(last) >= 0;
    }

    private record Line(String speaker, String text) {
    }
}
