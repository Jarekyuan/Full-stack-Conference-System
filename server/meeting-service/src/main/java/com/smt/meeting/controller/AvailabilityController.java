package com.smt.meeting.controller;

import com.smt.common.context.UserContext;
import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.common.result.Result;
import com.smt.meeting.dto.AvailabilityQueryDTO;
import com.smt.meeting.service.AvailabilityService;
import com.smt.meeting.vo.AvailabilityVO;
import com.smt.meeting.vo.RoomCalendarVO;
import com.smt.meeting.vo.RoomCalendarsVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 可用性与日历接口（对外路径 /api/rooms/**）
 * 全员可查（只读，登录即可）；与 RoomController 共享 /rooms 前缀，路径无冲突。
 */
@RestController
@RequestMapping("/rooms")
@RequiredArgsConstructor
public class AvailabilityController {

    private final AvailabilityService availabilityService;

    /** 批量可用性查询（预约表单实时校验） */
    @PostMapping("/availability")
    public Result<AvailabilityVO> availability(@Valid @RequestBody AvailabilityQueryDTO dto) {
        return Result.success(availabilityService.check(dto));
    }

    /**
     * 批量周日程（T6.3）：一次取多个会议室，前端日历页只发一个请求。
     * `roomIds` 逗号分隔（如 `roomIds=1,2,3`）；上限 50，避免被当成批量导出接口。
     */
    @GetMapping("/calendar")
    public Result<RoomCalendarsVO> calendars(@RequestParam String week,
                                             @RequestParam List<Long> roomIds) {
        if (roomIds.size() > 50) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "一次最多查询 50 个会议室");
        }
        LocalDate weekDate = parseWeek(week);
        return Result.success(availabilityService.calendars(roomIds, weekDate, UserContext.getUserId()));
    }

    /** 单会议室某周日程（week 传该周任意一天，格式 yyyy-MM-dd） */
    @GetMapping("/{id}/calendar")
    public Result<RoomCalendarVO> calendar(@PathVariable Long id, @RequestParam String week) {
        LocalDate weekDate = parseWeek(week);
        // userId 用于给每条会议打“邀请我的”标记（T6.1）；权限仍是“登录即可”
        return Result.success(availabilityService.calendar(id, weekDate, UserContext.getUserId()));
    }

    /** 日期格式错误按参数错误返回（10001），不被 500 兜底吞掉 */
    private LocalDate parseWeek(String week) {
        try {
            return LocalDate.parse(week);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
    }
}
