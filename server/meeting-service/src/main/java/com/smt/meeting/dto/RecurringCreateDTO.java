package com.smt.meeting.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建周期会议（T5.1，契约见 docs/04 4.9）。
 *
 * <p>用 {@code startMinutes}（当天分钟数）而不是 LocalTime：它和表字段一致，
 * 且"09:00 开始"这件事在跨时区/夏令时的环境里不会因为时区换算而漂移。
 */
@Data
public class RecurringCreateDTO {

    @NotNull(message = "会议室不能为空")
    private Long roomId;

    @NotBlank(message = "会议主题不能为空")
    @Size(max = 100, message = "会议主题不能超过 100 字")
    private String title;

    /** 1=周一 … 7=周日 */
    @NotNull(message = "重复星期不能为空")
    @Min(value = 1, message = "重复星期取值为 1~7")
    @Max(value = 7, message = "重复星期取值为 1~7")
    private Integer dayOfWeek;

    /** 当天分钟数（540=09:00）；上限 1439 只保证"是合法时刻"，与历时长度的关系由服务端校验 */
    @NotNull(message = "开始时间不能为空")
    @Min(value = 0, message = "开始时间非法")
    @Max(value = 1439, message = "开始时间非法")
    private Integer startMinutes;

    @NotNull(message = "会议时长不能为空")
    @Min(value = 1, message = "会议时长非法")
    private Integer durationMinutes;

    @NotNull(message = "重复开始日期不能为空")
    private LocalDate startDate;

    @NotNull(message = "重复结束日期不能为空")
    private LocalDate endDate;
}
