package com.smt.meeting.service;

import com.smt.meeting.mapper.MeetingMapper;
import com.smt.meeting.mapper.MeetingParticipantMapper;
import com.smt.meeting.vo.MeetingDetailVO;
import com.smt.meeting.vo.MinutesDetailVO;
import com.smt.meeting.vo.MinutesInsightsVO;
import com.smt.meeting.vo.MinutesSpeakerVO;
import com.smt.meeting.vo.MinutesSummaryVO;
import com.smt.meeting.vo.TranscriptSegmentVO;
import com.smt.meeting.vo.TranscriptVO;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 10：Word 导出。
 *
 * <p>重点钉住：构建结果可被 POI 读回，且包含会议信息（含 Mapper 补装的参会人）、
 * AI 纪要（含待办表格）、洞察（含发言人表格）与带时间轴的完整转写；
 * 历史脏数据（summary 列表/字段为 null）不会让导出失败。</p>
 */
class MinutesExportServiceTest {

    private static final Long MEETING_ID = 42L;
    private static final Long USER_ID = 4L;

    private MinutesService minutesService;
    private MeetingMapper meetingMapper;
    private MeetingParticipantMapper participantMapper;
    private MinutesExportService service;

    @BeforeEach
    void setUp() {
        minutesService = mock(MinutesService.class);
        meetingMapper = mock(MeetingMapper.class);
        participantMapper = mock(MeetingParticipantMapper.class);
        service = new MinutesExportService(minutesService, meetingMapper, participantMapper);
    }

    @Test
    void buildWritesMeetingSummaryInsightsAndTranscript() throws IOException {
        when(minutesService.detail(MEETING_ID, USER_ID)).thenReturn(detailWithSummary());
        when(minutesService.transcript(MEETING_ID, USER_ID))
                .thenReturn(new TranscriptVO(List.of(
                        segment(1000L, "0", "你好"),
                        segment(90_000_000L, "1", "继续"))));
        when(meetingMapper.selectDetailById(MEETING_ID)).thenReturn(meeting());
        when(participantMapper.selectDetailByMeetingId(MEETING_ID))
                .thenReturn(List.of(participant(7L, "李四")));

        MinutesExportService.ExportDocument exported = service.build(MEETING_ID, USER_ID);

        assertThat(exported.fileName()).contains("周会").endsWith(".docx");
        byte[] bytes = exported.bytes();
        assertThat(readParagraphText(bytes)).contains(
                "周会",
                "会议室：紫禁城",
                "组织人：王五",
                "会议时间：2026-10-18 10:00 至 2026-10-18 11:00",
                "参会人：李四",
                "摘要：会议摘要正文",
                "· 结论一",
                "· 采用方案 B（对比讨论后）",
                "· 风险一",
                "[00:00:01] 张三：你好",
                "[25:00:00] 说话人 1：继续");

        try (XWPFDocument document = open(bytes)) {
            assertThat(document.getTables()).hasSize(2);

            XWPFTable actions = document.getTables().get(0);
            assertThat(actions.getRow(0).getCell(0).getText()).isEqualTo("任务");
            assertThat(actions.getRow(1).getCell(0).getText()).isEqualTo("补报告");
            assertThat(actions.getRow(1).getCell(1).getText()).isEqualTo("张三");
            assertThat(actions.getRow(1).getCell(2).getText()).isEqualTo("2026-10-25");
            assertThat(actions.getRow(1).getCell(3).getText()).isEqualTo("HIGH");

            XWPFTable insights = document.getTables().get(1);
            assertThat(insights.getRow(0).getCell(0).getText()).isEqualTo("发言人");
            assertThat(insights.getRow(1).getCell(0).getText()).isEqualTo("张三");
            assertThat(insights.getRow(1).getCell(1).getText()).isEqualTo("0分30秒");
            assertThat(insights.getRow(1).getCell(2).getText()).isEqualTo("50%");
            assertThat(insights.getRow(1).getCell(3).getText()).isEqualTo("2");
        }
    }

    @Test
    void buildToleratesMissingAiSummary() throws IOException {
        MinutesDetailVO detail = new MinutesDetailVO();
        detail.setSpeakers(List.of());
        when(minutesService.detail(MEETING_ID, USER_ID)).thenReturn(detail);
        when(minutesService.transcript(MEETING_ID, USER_ID))
                .thenReturn(new TranscriptVO(List.of()));

        MinutesExportService.ExportDocument exported = service.build(MEETING_ID, USER_ID);

        assertThat(readParagraphText(exported.bytes())).contains("AI 纪要尚未生成。");
    }

    @Test
    void buildUsesDashesForMissingRoomAndParticipants() throws IOException {
        MeetingDetailVO meeting = new MeetingDetailVO();
        meeting.setTitle("周会");
        meeting.setOrganiserName("王五");
        meeting.setStartTime(LocalDateTime.of(2026, 10, 18, 10, 0));
        meeting.setEndTime(LocalDateTime.of(2026, 10, 18, 11, 0));
        when(meetingMapper.selectDetailById(MEETING_ID)).thenReturn(meeting);
        when(participantMapper.selectDetailByMeetingId(MEETING_ID)).thenReturn(List.of());
        MinutesDetailVO detail = new MinutesDetailVO();
        detail.setSpeakers(List.of());
        when(minutesService.detail(MEETING_ID, USER_ID)).thenReturn(detail);
        when(minutesService.transcript(MEETING_ID, USER_ID))
                .thenReturn(new TranscriptVO(List.of()));

        MinutesExportService.ExportDocument exported = service.build(MEETING_ID, USER_ID);

        assertThat(readParagraphText(exported.bytes()))
                .contains("会议室：-", "参会人：-");
    }

    @Test
    void buildToleratesSummaryWithNullLists() throws IOException {
        MinutesSummaryVO summary = new MinutesSummaryVO();
        summary.setSummary("仅有摘要");
        MinutesDetailVO detail = new MinutesDetailVO();
        detail.setSummary(summary);
        detail.setSpeakers(List.of());
        when(minutesService.detail(MEETING_ID, USER_ID)).thenReturn(detail);
        when(minutesService.transcript(MEETING_ID, USER_ID))
                .thenReturn(new TranscriptVO(List.of()));

        MinutesExportService.ExportDocument exported = service.build(MEETING_ID, USER_ID);

        String text = readParagraphText(exported.bytes());
        assertThat(text).contains("摘要：仅有摘要", "关键结论：", "决策：", "待办：", "风险：");
        try (XWPFDocument document = open(exported.bytes())) {
            // 只有待办表格；洞察为 null 时不建表。
            assertThat(document.getTables()).hasSize(1);
        }
    }

    @Test
    void buildToleratesNullElementsAndBlankFields() throws IOException {
        MinutesSummaryVO summary = new MinutesSummaryVO();
        summary.setSummary(" ");
        summary.setKeyPoints(Arrays.asList("结论一", null));
        MinutesSummaryVO.DecisionVO blankDecision = new MinutesSummaryVO.DecisionVO();
        summary.setDecisions(Arrays.asList(null, blankDecision));
        summary.setActionItems(Arrays.asList(new MinutesSummaryVO.ActionItemVO(), null));
        summary.setRisks(Arrays.asList(null, "风险一"));
        MinutesDetailVO detail = new MinutesDetailVO();
        detail.setSummary(summary);
        detail.setSpeakers(List.of());
        when(minutesService.detail(MEETING_ID, USER_ID)).thenReturn(detail);
        when(minutesService.transcript(MEETING_ID, USER_ID))
                .thenReturn(new TranscriptVO(List.of()));

        MinutesExportService.ExportDocument exported = service.build(MEETING_ID, USER_ID);

        String text = readParagraphText(exported.bytes());
        assertThat(text).contains("摘要：-", "· 结论一", "· -", "风险一");
        try (XWPFDocument document = open(exported.bytes())) {
            XWPFTable actions = document.getTables().get(0);
            assertThat(actions.getRow(1).getCell(0).getText()).isEqualTo("-");
            assertThat(actions.getRow(1).getCell(1).getText()).isEqualTo("-");
            assertThat(actions.getRow(1).getCell(2).getText()).isEqualTo("-");
            assertThat(actions.getRow(1).getCell(3).getText()).isEqualTo("-");
        }
    }

    @Test
    void buildReturnsFileNameWithMeetingTitle() throws IOException {
        when(minutesService.detail(MEETING_ID, USER_ID)).thenReturn(detailWithSummary());
        when(minutesService.transcript(MEETING_ID, USER_ID))
                .thenReturn(new TranscriptVO(List.of()));
        when(meetingMapper.selectDetailById(MEETING_ID)).thenReturn(meeting());

        assertThat(service.build(MEETING_ID, USER_ID).fileName())
                .contains("周会")
                .endsWith(".docx");
    }

    // ---------- 夹具 ----------

    private MeetingDetailVO meeting() {
        MeetingDetailVO meeting = new MeetingDetailVO();
        meeting.setTitle("周会");
        meeting.setRoomName("紫禁城");
        meeting.setOrganiserName("王五");
        meeting.setStartTime(LocalDateTime.of(2026, 10, 18, 10, 0));
        meeting.setEndTime(LocalDateTime.of(2026, 10, 18, 11, 0));
        // selectDetailById 不装配参会人，导出服务需通过 MeetingParticipantMapper 补齐。
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
        insights.setTotalSegments(2);
        insights.setSpeakers(List.of(speakerInsight("张三", 30_000L, 0.5, 2)));

        MinutesDetailVO detail = new MinutesDetailVO();
        detail.setSummary(summary);
        detail.setSpeakers(List.of(new MinutesSpeakerVO("0", "张三", "#7D8CFF")));
        detail.setInsights(insights);
        return detail;
    }

    private MinutesInsightsVO.SpeakerInsightVO speakerInsight(
            String displayName, Long speakingMs, Double talkRatio, Integer segmentCount) {
        MinutesInsightsVO.SpeakerInsightVO speaker = new MinutesInsightsVO.SpeakerInsightVO();
        speaker.setDisplayName(displayName);
        speaker.setSpeakingMs(speakingMs);
        speaker.setTalkRatio(talkRatio);
        speaker.setSegmentCount(segmentCount);
        return speaker;
    }

    private MeetingDetailVO.ParticipantVO participant(Long userId, String realName) {
        MeetingDetailVO.ParticipantVO participant = new MeetingDetailVO.ParticipantVO();
        participant.setUserId(userId);
        participant.setRealName(realName);
        return participant;
    }

    private TranscriptSegmentVO segment(long startMs, String speakerKey, String text) {
        TranscriptSegmentVO segment = new TranscriptSegmentVO();
        segment.setSeq(0);
        segment.setSpeakerKey(speakerKey);
        segment.setStartMs(startMs);
        segment.setEndMs(startMs + 1000);
        segment.setText(text);
        return segment;
    }

    private XWPFDocument open(byte[] bytes) throws IOException {
        return new XWPFDocument(new ByteArrayInputStream(bytes));
    }

    private String readParagraphText(byte[] bytes) throws IOException {
        try (XWPFDocument document = open(bytes)) {
            return document.getParagraphs().stream()
                    .map(paragraph -> paragraph.getText() == null ? "" : paragraph.getText())
                    .collect(Collectors.joining("\n"));
        }
    }
}
