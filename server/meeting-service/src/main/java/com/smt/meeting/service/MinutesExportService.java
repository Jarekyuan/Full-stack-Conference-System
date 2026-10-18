package com.smt.meeting.service;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.aop.Log;
import com.smt.meeting.mapper.MeetingMapper;
import com.smt.meeting.mapper.MeetingParticipantMapper;
import com.smt.meeting.vo.MeetingDetailVO;
import com.smt.meeting.vo.MinutesDetailVO;
import com.smt.meeting.vo.MinutesSpeakerVO;
import com.smt.meeting.vo.MinutesSummaryVO;
import com.smt.meeting.vo.TranscriptSegmentVO;
import com.smt.meeting.vo.TranscriptVO;
import lombok.RequiredArgsConstructor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 会议纪要 Word 导出（M6）：会议信息 + AI 结构化纪要 + 洞察 + 完整转写。
 *
 * <p>{@link #build} 先把整份文档构建到内存，成功后才交给控制器写响应头——
 * 构建期间的业务异常（70001/10003）或 IO 异常仍能由全局异常处理器输出 JSON，
 * 不会出现"响应头已是 docx、响应体却是 Result"的坏组合。</p>
 *
 * <p>权限复用 {@link MinutesService#detail} / {@link MinutesService#transcript} 内的
 * 可见性校验，导出入口不另做判断，避免两条路径的权限口径漂移。</p>
 */
@Service
@RequiredArgsConstructor
public class MinutesExportService {

    private static final DateTimeFormatter FILE_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final MinutesService minutesService;
    private final MeetingMapper meetingMapper;
    private final MeetingParticipantMapper participantMapper;

    /** 构建好的导出物：字节与文件名一起返回，控制器确认全部成功后再写响应头。 */
    public record ExportDocument(byte[] bytes, String fileName) {
    }

    @Log(type = "MINUTES_EXPORT")
    public ExportDocument build(Long meetingId, Long userId) throws IOException {
        MinutesDetailVO detail = minutesService.detail(meetingId, userId);
        if (detail == null) {
            throw new BusinessException(ErrorCode.MINUTES_NOT_FOUND);
        }
        TranscriptVO transcript = minutesService.transcript(meetingId, userId);
        MeetingDetailVO meeting = meetingMapper.selectDetailById(meetingId);
        if (meeting != null) {
            // selectDetailById 不装配 participants（由 Service 另行查），这里补上后才不会导出成“-”。
            meeting.setParticipants(participantMapper.selectDetailByMeetingId(meetingId));
        }

        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            title(document, documentTitle(meeting));
            paragraph(document, "会议室：" + roomName(meeting)
                    + "　组织人：" + organiserName(meeting));
            paragraph(document, "会议时间：" + meetingTime(meeting));
            paragraph(document, "参会人：" + participantNames(meeting));

            heading(document, "AI 会议纪要");
            if (detail.getSummary() == null) {
                paragraph(document, "AI 纪要尚未生成。");
            } else {
                writeSummary(document, detail.getSummary());
            }

            heading(document, "会议洞察");
            if (detail.getInsights() != null) {
                double coverage = detail.getInsights().getSpeechCoverage() == null
                        ? 0.0 : detail.getInsights().getSpeechCoverage();
                paragraph(document, "录音时长："
                        + formatDuration(detail.getInsights().getDurationMs())
                        + "　总发言时长：" + formatDuration(detail.getInsights().getSpeakingMs())
                        + "　发言覆盖率：" + Math.round(coverage * 100) + "%");
                XWPFTable table = document.createTable();
                table.getRow(0).getCell(0).setText("发言人");
                table.getRow(0).addNewTableCell().setText("发言时长");
                table.getRow(0).addNewTableCell().setText("占比");
                table.getRow(0).addNewTableCell().setText("分段数");
                for (var speaker : safeList(detail.getInsights().getSpeakers())) {
                    if (speaker == null) {
                        continue;
                    }
                    var row = table.createRow();
                    row.getCell(0).setText(orDash(speaker.getDisplayName()));
                    row.getCell(1).setText(formatDuration(speaker.getSpeakingMs()));
                    double ratio = speaker.getTalkRatio() == null ? 0.0 : speaker.getTalkRatio();
                    row.getCell(2).setText(Math.round(ratio * 100) + "%");
                    row.getCell(3).setText(String.valueOf(
                            speaker.getSegmentCount() == null ? 0 : speaker.getSegmentCount()));
                }
            }

            heading(document, "完整转写");
            Map<String, String> names = safeList(detail.getSpeakers()).stream()
                    .filter(speaker -> speaker != null && speaker.getSpeakerKey() != null)
                    .collect(Collectors.toMap(
                            MinutesSpeakerVO::getSpeakerKey,
                            speaker -> orDash(speaker.getDisplayName()),
                            (first, second) -> first));
            for (TranscriptSegmentVO segment : safeList(transcript.getSegments())) {
                if (segment == null) {
                    continue;
                }
                long startMs = segment.getStartMs() == null ? 0L : segment.getStartMs();
                paragraph(document, "[" + MinutesPromptBuilder.formatOffset(startMs) + "] "
                        + names.getOrDefault(segment.getSpeakerKey(),
                        "说话人 " + segment.getSpeakerKey())
                        + "：" + orDash(segment.getText()));
            }
            document.write(output);
            return new ExportDocument(output.toByteArray(), fileName(meeting));
        }
    }

    private void writeSummary(XWPFDocument document, MinutesSummaryVO summary) {
        paragraph(document, "摘要：" + orDash(summary.getSummary()));
        paragraph(document, "关键结论：");
        for (String point : safeList(summary.getKeyPoints())) {
            if (point != null && !point.isBlank()) {
                paragraph(document, "· " + point);
            }
        }
        paragraph(document, "决策：");
        for (MinutesSummaryVO.DecisionVO decision : safeList(summary.getDecisions())) {
            if (decision == null) {
                continue;
            }
            paragraph(document, "· " + orDash(decision.getContent())
                    + (decision.getContext() == null || decision.getContext().isBlank()
                    ? "" : "（" + decision.getContext() + "）"));
        }
        paragraph(document, "待办：");
        XWPFTable table = document.createTable();
        table.getRow(0).getCell(0).setText("任务");
        table.getRow(0).addNewTableCell().setText("负责人");
        table.getRow(0).addNewTableCell().setText("截止时间");
        table.getRow(0).addNewTableCell().setText("优先级");
        for (MinutesSummaryVO.ActionItemVO item : safeList(summary.getActionItems())) {
            if (item == null) {
                continue;
            }
            var row = table.createRow();
            row.getCell(0).setText(orDash(item.getTask()));
            row.getCell(1).setText(orDash(item.getOwner()));
            row.getCell(2).setText(orDash(item.getDueDate()));
            row.getCell(3).setText(orDash(item.getPriority()));
        }
        paragraph(document, "风险：");
        for (String risk : safeList(summary.getRisks())) {
            if (risk != null && !risk.isBlank()) {
                paragraph(document, "· " + risk);
            }
        }
    }

    private String fileName(MeetingDetailVO meeting) {
        String title = meeting == null ? null : meeting.getTitle();
        return "会议纪要-" + orFallback(title, "会议") + "-"
                + LocalDate.now().format(FILE_DATE) + ".docx";
    }

    private String documentTitle(MeetingDetailVO meeting) {
        return meeting == null ? "会议纪要" : orFallback(meeting.getTitle(), "会议纪要");
    }

    private String roomName(MeetingDetailVO meeting) {
        return meeting == null ? "-" : orDash(meeting.getRoomName());
    }

    private String organiserName(MeetingDetailVO meeting) {
        return meeting == null ? "-" : orDash(meeting.getOrganiserName());
    }

    private String meetingTime(MeetingDetailVO meeting) {
        if (meeting == null || meeting.getStartTime() == null || meeting.getEndTime() == null) {
            return "-";
        }
        return meeting.getStartTime().format(DATE_TIME)
                + " 至 " + meeting.getEndTime().format(DATE_TIME);
    }

    private String participantNames(MeetingDetailVO meeting) {
        if (meeting == null || meeting.getParticipants() == null) {
            return "-";
        }
        String names = meeting.getParticipants().stream()
                .filter(participant -> participant != null
                        && participant.getRealName() != null
                        && !participant.getRealName().isBlank())
                .map(MeetingDetailVO.ParticipantVO::getRealName)
                .collect(Collectors.joining("、"));
        return names.isEmpty() ? "-" : names;
    }

    private void title(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setBold(true);
        run.setFontSize(18);
        run.setText(text);
    }

    private void heading(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setBold(true);
        run.setFontSize(13);
        run.setText(text);
    }

    private void paragraph(XWPFDocument document, String text) {
        document.createParagraph().createRun().setText(text);
    }

    private String formatDuration(Long ms) {
        long totalSeconds = Math.max(0, ms == null ? 0 : ms / 1000);
        return String.format("%d分%02d秒", totalSeconds / 60, totalSeconds % 60);
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String orFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
