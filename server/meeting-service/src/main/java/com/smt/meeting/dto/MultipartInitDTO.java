package com.smt.meeting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MultipartInitDTO {

    @NotBlank(message = "业务类型不能为空")
    @Size(max = 32, message = "业务类型长度不能超过 32")
    @Pattern(regexp = "MINUTES_AUDIO", message = "业务类型仅支持 MINUTES_AUDIO")
    private String bizType;

    @NotNull(message = "业务ID不能为空")
    @Positive(message = "业务ID必须为正数")
    private Long bizId;

    @NotBlank(message = "文件名不能为空")
    @Size(max = 255, message = "文件名长度不能超过 255")
    private String fileName;

    @NotNull(message = "文件大小不能为空")
    @Positive(message = "文件大小必须为正数")
    private Long fileSize;

    @NotBlank(message = "文件类型不能为空")
    @Size(max = 100, message = "文件类型长度不能超过 100")
    private String contentType;
}
