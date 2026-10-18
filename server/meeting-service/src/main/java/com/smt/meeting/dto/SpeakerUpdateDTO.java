package com.smt.meeting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SpeakerUpdateDTO {

    @NotBlank(message = "发言人名称不能为空")
    @Size(max = 50, message = "发言人名称长度不能超过 50")
    private String displayName;

    @NotBlank(message = "颜色不能为空")
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "颜色必须是 #RRGGBB")
    private String color;
}
