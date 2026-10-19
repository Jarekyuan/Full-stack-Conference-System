package com.smt.meeting.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 发起腾讯 ASR 转写：音频已通过分片上传完成，只需要 fileId。
 */
@Data
public class MinutesTranscribeDTO {

    @NotNull(message = "音频文件ID不能为空")
    @Positive(message = "音频文件ID必须为正数")
    private Long fileId;
}
