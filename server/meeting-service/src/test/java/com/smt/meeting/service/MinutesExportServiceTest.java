package com.smt.meeting.service;

import com.smt.meeting.mapper.MeetingMapper;
import com.smt.meeting.vo.MeetingDetailVO;
import com.smt.meeting.vo.MinutesDetailVO;
import com.smt.meeting.vo.MinutesInsightsVO;
import com.smt.meeting.vo.MinutesSpeakerVO;
import com.smt.meeting.vo.MinutesSummaryVO;
import com.smt.meeting.vo.TranscriptSegmentVO;
import com.smt.meeting.vo.TranscriptVO;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 10：Word 导出。
 *
 * <p>重点钉住：导出的 docx 能被 POI 读回，且包含会议信息、AI 纪要、洞察与带时间轴的转写；
 * 无 AI 纪要时有明确占位；文件名带会议标题与 .docx 后缀。</p>
 */
class MinutesExportServiceTest {

    private static final Long MEETING_ID = 42L;
    private static final Long USER_ID = 4L;

    private MinutesService minutesService;
    private MeetingMapper meetingMapper;
    private MinutesExportService service;

    @BeforeEach
    void setUp() {
        minutesService = mock(MinutesService.class);
        meetingMapper = mock(MeetingMapper.class);
        service = new MinutesExportService(minutesService, meetingMapper);
    }

    @Test
    void exportWritesMeetingSummaryInsightsAndTranscript() throws IOException {
        when(minutesService.detail(MEETING_ID, USER_ID)).thenReturn(detailWithSummary());
        when(minutesService.transcript(MEETING_ID, USER_ID))
                .thenReturn(new TranscriptVO(List.of(segment())));
        when(meetingMapper.selectDetailById(MEETING_ID)).thenReturn(meeting());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.export(MEETING_ID, USER_ID, output);

        byte[] bytes = output.toByteArray();
        assertThat(readParagraphText(bytes))
                .contains("周会", "会议摘要", "结论一", "[00:00:01] 张三：你好");

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertThat(document.getTables()).hasSize(2);
            assertThat(document.getTables().get(0).getRow(0).getCell(0).getText())
                    .isEqualTo("任务");
            assertThat(document.getTables().get(1).getRow(0).getCell(0).getText())
                    .isEqualTo("发言人");
        }
    }

    @Test
    void exportMarksMissingAiSummary() throws IOException {
        MinutesDetailVO detail = new MinutesDetailVO();
        detail.setSpeakers(List.of());
        detail.setInsights(null);
        when(minutesService.detail(MEETING_ID, USER_ID)).thenReturn(detail);
        when(minutesService.transcript(MEETING_ID, USER_ID))
                .thenReturn(new TranscriptVO(List.of()));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.export(MEETING_ID, USER_ID, output);

        assertThat(readParagraphText(output.toByteArray()))
                .contains("AI 纪要尚未生成。");
    }

    @Test
    void fileNameContainsTitleAndDocxExtension() {
        when(meetingMapper.selectDetailById(MEETING_ID)).thenReturn(meeting());

        assertThat(service.fileName(MEETING_ID))
                .contains("周会")
                .endsWith(".docx");
    }

    // ---------- 夹具 ----------

    private MeetingDetailVO meeting() {
        MeetingDetailVO meeting = new MeetingDetailVO();
        meeting.setTitle("周会");
        meeting.setRoomName("紫禁城");
        meeting.setOrganiserName("李四");
        meeting.setStartTime(LocalDateTime.of(2026, 10, 18, 10, 0));
        meeting.setEndTime(LocalDateTime.of(2026, 10, 18, 11, 0));
        meeting.setParticipants(List.of());
        return meeting;
    }

    private MinutesDetailVO detailWithSummary() {
        MinutesSummaryVO summary = new MinutesSummaryVO();
        summary.setSummary("会议摘要正文");
        summary.setKeyPoints(List.of("结论一"));

        MinutesSummaryVO.DecisionVO decision = new MinutesSummaryVO.DecisionVO();
        decision.setContent("采用方案 B");
        decision.setContext("对比讨论后");
        summary.setDecisions(List.of(decision));

        MinutesSummaryVO.ActionItemVO item = new MinutesSummaryVO.ActionItemVO();
        item.setTask("补报告");
        item.setOwner("张三");
        item.setDueDate("2026-10-25");
        item.setPriority("HIGH");
        summary.setActionItems(List.of(item));
        summary.setRisks(List.of("风险一"));

        MinutesInsightsVO insights = new MinutesInsightsVO();
        insights.setDurationMs(60_000L);
        insights.setSpeakingMs(30_000L);
        insights.setSpeechCoverage(0.5);
        insights.setTotalSegments(1);
        insights.setSpeakers(List.of());

        MinutesDetailVO detail = new MinutesDetailVO();
        detail.setSummary(summary);
        detail.setSpeakers(List.of(new MinutesSpeakerVO("0", "张三", "#7D8CFF")));
        detail.setInsights(insights);
        return detail;
    }

    private TranscriptSegmentVO segment() {
        TranscriptSegmentVO segment = new TranscriptSegmentVO();
        segment.setSeq(0);
        segment.setSpeakerKey("0");
        segment.setStartMs(1000L);
        segment.setEndMs(2000L);
        segment.setText("你好");
        return segment;
    }

    private String readParagraphText(byte[] bytes) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            return document.getParagraphs().stream()
                    .map(paragraph -> paragraph.getText() == null ? "" : paragraph.getText())
                    .collect(Collectors.joining("\n"));
        }
    }
}
