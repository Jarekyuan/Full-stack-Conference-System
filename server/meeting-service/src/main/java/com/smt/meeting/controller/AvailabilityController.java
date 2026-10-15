package com.smt.meeting.controller;

import com.smt.common.exception.BusinessException;
import com.smt.common.result.ErrorCode;
import com.smt.common.result.Result;
import com.smt.meeting.dto.AvailabilityQueryDTO;
import com.smt.meeting.service.AvailabilityService;
import com.smt.meeting.vo.AvailabilityVO;
import com.smt.meeting.vo.RoomCalendarVO;
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

    /** 单会议室某周日程（week 传该周任意一天，格式 yyyy-MM-dd） */
    @GetMapping("/{id}/calendar")
    public Result<RoomCalendarVO> calendar(@PathVariable Long id, @RequestParam String week) {
        LocalDate weekDate;
        try {
            weekDate = LocalDate.parse(week);
        } catch (DateTimeParseException e) {
            // 日期格式错误按参数错误返回（10001），不被 500 兜底吞掉
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
        return Result.success(availabilityService.calendar(id, weekDate));
    }
}
