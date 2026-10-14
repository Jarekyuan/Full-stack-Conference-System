package com.smt.meeting.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 会议室新增/修改入参（ADMIN）
 */
@Data
public class RoomSaveDTO {

    @NotBlank(message = "会议室名称不能为空")
    @Size(max = 50, message = "名称最长 50 字符")
    private String name;

    @NotNull(message = "分组不能为空")
    private Long groupId;

    @NotNull(message = "容纳人数不能为空")
    @Min(value = 1, message = "容纳人数至少 1")
    @Max(value = 200, message = "容纳人数最多 200")
    private Integer capacity;

    @Size(max = 100, message = "位置最长 100 字符")
    private String location;

    private List<String> images;

    private List<String> deviceTags;

    /** 状态：0 可用 / 1 维护中（修改时可选，为空则不修改） */
    @Min(value = 0, message = "状态只能是 0 或 1")
    @Max(value = 1, message = "状态只能是 0 或 1")
    private Integer status;
}
