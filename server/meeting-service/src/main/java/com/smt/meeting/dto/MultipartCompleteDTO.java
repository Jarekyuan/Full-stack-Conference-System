package com.smt.meeting.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class MultipartCompleteDTO {

    /**
     * 分片列表。
     *
     * <p>不在此处使用 {@code @NotEmpty} 或元素级 {@code @NotNull}：空/null 列表与 null 元素
     * 都属于"分片不完整"的业务语义（70007），统一由 Service 处理，
     * 避免同一个错误在 Bean Validation 层变成 10001。</p>
     */
    @Valid
    private List<Part> parts;

    @Data
    public static class Part {

        @NotNull(message = "分片号不能为空")
        @Min(value = 1, message = "分片号必须从 1 开始")
        private Integer partNumber;

        @NotBlank(message = "分片 ETag 不能为空")
        @Size(max = 128, message = "分片 ETag 长度不能超过 128")
        private String etag;
    }
}
