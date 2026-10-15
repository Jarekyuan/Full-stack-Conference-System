package com.smt.meeting.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 延长会议入参（docs/04 4.6）
 *
 * `minutes` 只做**基础合法性**校验（正整数、给个防呆上限）：
 * 真正的上限由服务端按 R-15 现算（下一场会议 − 锁定缓冲 / 当日 24:00），超了回 40006 ——
 * 客户端传来的任何"上限"都不可信，前端弹窗里的数字只用于展示。
 */
@Data
public class MeetingExtendDTO {

    @NotNull(message = "延长时间不能为空")
    @Min(value = 1, message = "至少延长 1 分钟")
    @Max(value = 1440, message = "单次延长不能超过 24 小时")
    private Integer minutes;
}
