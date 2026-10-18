package com.smt.meeting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MinutesImportDTO {
    @NotNull @Positive private Long fileId;
    @NotBlank private String transcriptText;
    @Positive private Long durationMs;
    @Size(max = 32) private String source;
}
