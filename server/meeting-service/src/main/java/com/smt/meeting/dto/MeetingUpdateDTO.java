package com.smt.meeting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 修改会议入参（docs/04 3.2：PUT /meetings/{id}）
 * version 为乐观锁版本号：前端从详情接口取回、提交时原样带回，后端按 version 校验并发修改。
 * 说明：参会人修改不在 T2.10 范围（聚焦"改期/改信息 + 乐观锁 + R-14"三核心点）。
 */
@Data
public class MeetingUpdateDTO {

    @NotBlank(message = "会议主题不能为空")
    @Size(max = 100, message = "主题最长 100 字符")
    private String title;

    @NotNull(message = "会议室不能为空")
    private Long roomId;

    @NotNull(message = "开始时间不能为空")
    private LocalDateTime startTime;

    @NotNull(message = "结束时间不能为空")
    private LocalDateTime endTime;

    @Size(max = 500, message = "备注最长 500 字符")
    private String remark;

    /** 乐观锁版本号（详情返回，提交原样带回） */
    @NotNull(message = "版本号不能为空")
    private Integer version;
}
