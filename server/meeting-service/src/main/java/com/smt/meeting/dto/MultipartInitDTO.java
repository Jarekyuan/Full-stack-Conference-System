package com.smt.meeting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MultipartInitDTO {
    @NotBlank private String bizType;
    @NotNull @Positive private Long bizId;
    @NotBlank @Size(max = 255) private String fileName;
    @NotNull @Positive private Long fileSize;
    @NotBlank @Size(max = 100) private String contentType;
}
