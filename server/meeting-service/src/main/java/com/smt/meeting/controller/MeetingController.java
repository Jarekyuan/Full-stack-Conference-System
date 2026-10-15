package com.smt.meeting.controller;

import com.smt.common.context.UserContext;
import com.smt.common.result.PageResult;
import com.smt.common.result.Result;
import com.smt.meeting.aop.Idempotent;
import com.smt.meeting.dto.MeetingCreateDTO;
import com.smt.meeting.dto.MeetingQueryDTO;
import com.smt.meeting.service.MeetingQueryService;
import com.smt.meeting.service.MeetingReserveService;
import com.smt.meeting.vo.MeetingCreateVO;
import com.smt.meeting.vo.MeetingDetailVO;
import com.smt.meeting.vo.MeetingVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final MeetingQueryService queryService;

    /**
     * 会议列表（docs/04 4.5）：scope(mine/all)+status+roomId+日期范围+keyword+分页
     * scope=all 时需 ADMIN/APPROVER（Service 层校验）
     */
    @GetMapping
    public Result<PageResult<MeetingVO>> list(MeetingQueryDTO query) {
        return Result.success(queryService.page(query));
    }

    /** 会议详情（含参会人明细 + 状态时间线；会议信息公开可查，见 Service 注释） */
    @GetMapping("/{id}")
    public Result<MeetingDetailVO> detail(@PathVariable Long id) {
        return Result.success(queryService.detail(id));
    }

    /**
     * 创建预约（docs/04 4.4）
     * 幂等：@Idempotent 读取 X-Idempotency-Token（T2.5）；分布式锁在 T2.6 接入。
     */
    @PostMapping
    @Idempotent("meeting")
    public Result<MeetingCreateVO> create(@Valid @RequestBody MeetingCreateDTO dto) {
        Long organiserId = UserContext.getUserId();
        return Result.success(reserveService.create(dto, organiserId));
    }
}
