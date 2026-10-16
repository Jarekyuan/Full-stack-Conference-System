package com.smt.meeting.controller;

import com.smt.common.context.UserContext;
import com.smt.common.result.PageResult;
import com.smt.common.result.Result;
import com.smt.meeting.aop.Idempotent;
import com.smt.meeting.aop.RateLimit;
import com.smt.meeting.dto.MeetingCancelDTO;
import com.smt.meeting.dto.MeetingCreateDTO;
import com.smt.meeting.dto.MeetingExtendDTO;
import com.smt.meeting.dto.MeetingQueryDTO;
import com.smt.meeting.dto.MeetingUpdateDTO;
import com.smt.meeting.service.MeetingQueryService;
import com.smt.meeting.service.MeetingReserveService;
import com.smt.meeting.vo.ExtendLimitVO;
import com.smt.meeting.vo.MeetingCreateVO;
import com.smt.meeting.vo.MeetingDetailVO;
import com.smt.meeting.vo.MeetingVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
     * 取消会议（组织人；docs/04 3.2）
     * 校验：归属 40004 → 取消截止 R-6（30005）→ 状态机（10/20 → 50）
     */
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id,
                               @Valid @RequestBody(required = false) MeetingCancelDTO dto) {
        String reason = dto == null ? null : dto.getReason();
        reserveService.cancel(id, reason, UserContext.getUserId());
        return Result.success();
    }

    /**
     * 修改会议（组织人；docs/04 3.2）
     * 乐观锁 version 校验并发 + R-14 审批分流重判
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody MeetingUpdateDTO dto) {
        reserveService.update(id, dto, UserContext.getUserId());
        return Result.success();
    }

    /**
     * 创建预约（docs/04 4.4）
     * 幂等：@Idempotent 读取 X-Idempotency-Token（T2.5）；分布式锁在 T2.6 接入。
     */
    @PostMapping
    @Idempotent("meeting")
    @RateLimit("reserve")
    public Result<MeetingCreateVO> create(@Valid @RequestBody MeetingCreateDTO dto) {
        Long organiserId = UserContext.getUserId();
        return Result.success(reserveService.create(dto, organiserId));
    }

    /** 可延长上限（docs/04 4.6，T3.4）：R-15 的计算在服务端，前端只负责展示 maxDelayMinutes + reason */
    @GetMapping("/{id}/extend-limit")
    public Result<ExtendLimitVO> extendLimit(@PathVariable Long id) {
        return Result.success(reserveService.extendLimit(id));
    }

    /** 延长会议（组织人；仅进行中，超上限 40006，与下一场冲突 40001） */
    @PostMapping("/{id}/extend")
    public Result<Void> extend(@PathVariable Long id, @Valid @RequestBody MeetingExtendDTO dto) {
        reserveService.extend(id, dto.getMinutes(), UserContext.getUserId());
        return Result.success();
    }

    /** 提前结束（组织人；仅进行中，30 → 40） */
    @PostMapping("/{id}/finish")
    public Result<Void> finish(@PathVariable Long id) {
        reserveService.finish(id, UserContext.getUserId());
        return Result.success();
    }
}
