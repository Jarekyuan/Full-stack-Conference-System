package com.smt.meeting.controller;

import com.smt.common.result.Result;
import com.smt.meeting.service.RoomService;
import com.smt.meeting.vo.RoomGroupVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会议室分组查询接口（对外路径 /api/room-groups）
 */
@RestController
@RequestMapping("/room-groups")
@RequiredArgsConstructor
public class RoomGroupController {

    private final RoomService roomService;

    @GetMapping
    public Result<List<RoomGroupVO>> list() {
        return Result.success(roomService.listGroups());
    }
}
