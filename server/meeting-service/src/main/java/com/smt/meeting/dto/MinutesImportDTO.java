package com.smt.meeting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MinutesImportDTO {

    @NotNull(message = "文件ID不能为空")
    @Positive(message = "文件ID必须为正数")
    private Long fileId;

    @NotBlank(message = "转写文本不能为空")
    private String transcriptText;

    @Positive(message = "录音时长必须为正数")
    private Long durationMs;

    @Size(max = 32, message = "转写来源长度不能超过 32")
    @Pattern(regexp = "TENCENT_ASR", message = "转写来源仅支持 TENCENT_ASR")
    private String source;
}
