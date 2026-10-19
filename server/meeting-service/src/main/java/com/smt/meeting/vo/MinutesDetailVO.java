package com.smt.meeting.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class MinutesDetailVO {

    private Long id;
    private Long meetingId;
    private AudioVO audio;
    private String transcriptSource;
    /** ASR 状态：0 未转写 / 10 待识别 / 20 识别中 / 30 成功 / 40 失败 */
    private Integer asrStatus;
    private String asrError;
    private Integer aiStatus;
    private String aiError;
    /** 当前 AI 供应商：deepseek / mock。前端据此提示“本地演示模式”。 */
    private String aiProvider;
    private String aiModel;
    private MinutesSummaryVO summary;
    private List<MinutesSpeakerVO> speakers;
    private MinutesInsightsVO insights;
    private MinutesPermissionVO permissions;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Data
    public static class AudioVO {
        private Long fileId;
        private String fileName;
        private Long sizeBytes;
        private Long durationMs;
    }
}
