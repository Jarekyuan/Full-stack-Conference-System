package com.smt.meeting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SpeakerUpdateDTO {
    @NotBlank @Size(max = 50) private String displayName;
    @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "颜色必须是 #RRGGBB") private String color;
}
