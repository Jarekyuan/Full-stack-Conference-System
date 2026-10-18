package com.smt.meeting.service;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.aop.Log;
import com.smt.meeting.mapper.MeetingMapper;
import com.smt.meeting.mapper.MeetingParticipantMapper;
import com.smt.meeting.vo.MeetingDetailVO;
import com.smt.meeting.vo.MinutesDetailVO;
import com.smt.meeting.vo.MinutesSummaryVO;
import com.smt.meeting.vo.TranscriptSegmentVO;
import com.smt.meeting.vo.TranscriptVO;
import lombok.RequiredArgsConstructor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 会议纪要 Word 导出（M6）：会议信息 + AI 结构化纪要 + 洞察 + 完整转写。
 *
 * <p>权限复用 {@link MinutesService#detail} / {@link MinutesService#transcript} 内的
 * 可见性校验，导出入口不另做判断，避免两条路径的权限口径漂移。</p>
 */
@Service
@RequiredArgsConstructor
public class MinutesExportService {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    private final MinutesService minutesService;
    private final MeetingMapper meetingMapper;
    private final MeetingParticipantMapper participantMapper;

    public String fileName(Long meetingId) {
        MeetingDetailVO meeting = meetingMapper.selectDetailById(meetingId);
        String title = meeting == null ? "会议" : meeting.getTitle();
        return "会议纪要-" + title + "-" + java.time.LocalDate.now().format(DATE) + ".docx";
    }

    @Log(type = "MINUTES_EXPORT")
    public void export(Long meetingId, Long userId, OutputStream output) throws IOException {
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

        try (XWPFDocument document = new XWPFDocument()) {
            title(document, meeting == null ? "会议纪要" : meeting.getTitle());
            paragraph(document, "会议室：" + (meeting == null ? "-" : meeting.getRoomName())
                    + "　组织人：" + (meeting == null ? "-" : meeting.getOrganiserName()));
            paragraph(document, "会议时间：" + (meeting == null ? "-"
                    : meeting.getStartTime() + " 至 " + meeting.getEndTime()));
            paragraph(document, "参会人：" + (meeting == null || meeting.getParticipants() == null
                    ? "-"
                    : meeting.getParticipants().stream()
                    .map(MeetingDetailVO.ParticipantVO::getRealName)
                    .collect(Collectors.joining("、"))));

            heading(document, "AI 会议纪要");
            if (detail.getSummary() == null) {
                paragraph(document, "AI 纪要尚未生成。");
            } else {
                writeSummary(document, detail.getSummary());
            }

            heading(document, "会议洞察");
            if (detail.getInsights() != null) {
                paragraph(document, "录音时长："
                        + formatDuration(detail.getInsights().getDurationMs())
                        + "　总发言时长：" + formatDuration(detail.getInsights().getSpeakingMs())
                        + "　发言覆盖率："
                        + Math.round(detail.getInsights().getSpeechCoverage() * 100) + "%");
                XWPFTable table = document.createTable();
                table.getRow(0).getCell(0).setText("发言人");
                table.getRow(0).addNewTableCell().setText("发言时长");
                table.getRow(0).addNewTableCell().setText("占比");
                table.getRow(0).addNewTableCell().setText("分段数");
                for (var speaker : detail.getInsights().getSpeakers()) {
                    var row = table.createRow();
                    row.getCell(0).setText(speaker.getDisplayName());
                    row.getCell(1).setText(formatDuration(speaker.getSpeakingMs()));
                    row.getCell(2).setText(Math.round(speaker.getTalkRatio() * 100) + "%");
                    row.getCell(3).setText(String.valueOf(speaker.getSegmentCount()));
                }
            }

            heading(document, "完整转写");
            Map<String, String> names = detail.getSpeakers().stream()
                    .collect(Collectors.toMap(
                            com.smt.meeting.vo.MinutesSpeakerVO::getSpeakerKey,
                            com.smt.meeting.vo.MinutesSpeakerVO::getDisplayName));
            List<TranscriptSegmentVO> segments = transcript.getSegments();
            for (TranscriptSegmentVO segment : segments) {
                paragraph(document, "[" + MinutesPromptBuilder.formatOffset(
                        segment.getStartMs()) + "] "
                        + names.getOrDefault(segment.getSpeakerKey(),
                        "说话人 " + segment.getSpeakerKey())
                        + "：" + segment.getText());
            }
            document.write(output);
        }
    }

    private void writeSummary(XWPFDocument document, MinutesSummaryVO summary) {
        paragraph(document, "摘要：" + summary.getSummary());
        paragraph(document, "关键结论：");
        for (String point : summary.getKeyPoints()) {
            paragraph(document, "· " + point);
        }
        paragraph(document, "决策：");
        for (MinutesSummaryVO.DecisionVO decision : summary.getDecisions()) {
            paragraph(document, "· " + decision.getContent()
                    + (decision.getContext() == null ? "" : "（" + decision.getContext() + "）"));
        }
        paragraph(document, "待办：");
        XWPFTable table = document.createTable();
        table.getRow(0).getCell(0).setText("任务");
        table.getRow(0).addNewTableCell().setText("负责人");
        table.getRow(0).addNewTableCell().setText("截止时间");
        table.getRow(0).addNewTableCell().setText("优先级");
        for (MinutesSummaryVO.ActionItemVO item : summary.getActionItems()) {
            var row = table.createRow();
            row.getCell(0).setText(item.getTask());
            row.getCell(1).setText(item.getOwner() == null ? "-" : item.getOwner());
            row.getCell(2).setText(item.getDueDate() == null ? "-" : item.getDueDate());
            row.getCell(3).setText(item.getPriority() == null ? "-" : item.getPriority());
        }
        paragraph(document, "风险：");
        for (String risk : summary.getRisks()) {
            paragraph(document, "· " + risk);
        }
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
}
