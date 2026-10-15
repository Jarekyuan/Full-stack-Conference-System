package com.smt.meeting.controller;

import com.smt.common.context.UserContext;
import com.smt.common.result.Result;
import com.smt.meeting.dto.MeetingCreateDTO;
import com.smt.meeting.service.MeetingReserveService;
import com.smt.meeting.vo.MeetingCreateVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会议接口（对外路径 /api/meetings/**）
 * 组织人身份来自网关注入的 X-User-Id（UserContext），前端不可伪造
 */
@RestController
@RequestMapping("/meetings")
@RequiredArgsConstructor
public class MeetingController {

    private final MeetingReserveService reserveService;

    /**
     * 创建预约（docs/04 4.4）
     * 幂等头 X-Idempotency-Token 在 T2.5 由 AOP 接入；分布式锁在 T2.6 接入。
     */
    @PostMapping
    public Result<MeetingCreateVO> create(@Valid @RequestBody MeetingCreateDTO dto) {
        Long organiserId = UserContext.getUserId();
        return Result.success(reserveService.create(dto, organiserId));
    }
}
