package com.smt.meeting.controller;

import com.smt.common.result.Result;
import com.smt.meeting.service.RoomService;
import com.smt.meeting.vo.RoomVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会议室查询接口（对外路径 /api/rooms/**）
 */
@RestController
@RequestMapping("/rooms")
@RequiredArgsConstructor
public class RoomController {

    private final RoomService roomService;

    /** 列表（筛选：groupId 精确 / capacity 下限 / status 精确） */
    @GetMapping
    public Result<List<RoomVO>> list(@RequestParam(required = false) Long groupId,
                                     @RequestParam(required = false) Integer capacity,
                                     @RequestParam(required = false) Integer status) {
        return Result.success(roomService.list(groupId, capacity, status));
    }

    /** 详情（含分组名与策略） */
    @GetMapping("/{id}")
    public Result<RoomVO> detail(@PathVariable Long id) {
        return Result.success(roomService.detail(id));
    }
}
