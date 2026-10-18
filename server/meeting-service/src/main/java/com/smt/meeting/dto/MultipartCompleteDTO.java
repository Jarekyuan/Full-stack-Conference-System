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
    @NotEmpty @Valid private List<Part> parts;

    @Data
    public static class Part {
        @NotNull @Min(1) private Integer partNumber;
        @NotBlank private String etag;
    }
}
