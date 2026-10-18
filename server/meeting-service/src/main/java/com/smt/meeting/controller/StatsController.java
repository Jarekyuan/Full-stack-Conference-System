package com.smt.meeting.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.smt.common.context.UserContext;
import com.smt.common.result.Result;
import com.smt.meeting.service.StatsService;
import com.smt.meeting.vo.MyMeetingStatsVO;
import com.smt.meeting.vo.RoomUsageVO;

/**
 * 统计接口（T5.3，契约 docs/04 4.10）。
 *
 * <p>权限按 docs/04 3.2 接口清单与 4.10 统计契约收口：**room-usage 仅 ADMIN**（全公司各会议室的
 * 使用率属于管理视角），
 * my-meetings 登录即可（只涉及自己的数据，且 SQL 里就带了 user_id 过滤——数据权限落在 SQL，不靠前端）。
 */
@RestController
@RequestMapping("/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    /** 会议室使用率（H-1）；start/end 缺省 = 近 30 天 */
    @GetMapping("/room-usage")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<RoomUsageVO> roomUsage(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return Result.success(statsService.roomUsage(start, end));
    }

    /** 个人会议统计（H-2，近 30 天） */
    @GetMapping("/my-meetings")
    public Result<MyMeetingStatsVO> myMeetings() {
        return Result.success(statsService.myMeetings(UserContext.getUserId()));
    }
}
