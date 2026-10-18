package com.smt.meeting.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class MultipartCompleteDTO {

    @NotEmpty(message = "分片列表不能为空")
    @Valid
    private List<Part> parts;

    @Data
    public static class Part {

        @NotNull(message = "分片号不能为空")
        @Min(value = 1, message = "分片号必须从 1 开始")
        private Integer partNumber;

        @NotBlank(message = "分片 ETag 不能为空")
        private String etag;
    }
}
