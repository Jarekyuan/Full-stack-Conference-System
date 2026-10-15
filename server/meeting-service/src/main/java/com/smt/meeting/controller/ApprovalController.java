package com.smt.meeting.controller;

import com.smt.common.context.UserContext;
import com.smt.common.result.PageResult;
import com.smt.common.result.Result;
import com.smt.meeting.dto.ApprovalDTO;
import com.smt.meeting.service.ApprovalService;
import com.smt.meeting.vo.ApprovalHistoryVO;
import com.smt.meeting.vo.ApprovalResultVO;
import com.smt.meeting.vo.ApprovalVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审批接口（对外路径 /api/approvals/**，docs/04 3.2）
 *
 * 权限用 `@PreAuthorize` 收口（与 RoomManage 等写接口一致）：非 APPROVER 由框架拦成 403，
 * 业务层不必再判一次角色——50003 留给将来的"审批范围"类判断（当前版本没有部门级范围）。
 * 审批人身份来自网关注入的 X-User-Id（UserContext），前端不可伪造。
 *
 * ⚠️ 管理员（ADMIN）也可以审批：docs/04 权限列把审批接口给 APPROVER，
 * 但演示/排障时 ADMIN 顶得上（种子数据里 admin 同时持有 APPROVER，因此这里显式列出两者保持可读性）。
 */
@RestController
@RequestMapping("/approvals")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService approvalService;

    /** 待审批列表（分页；按会议开始时间升序） */
    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('APPROVER', 'ADMIN')")
    public Result<PageResult<ApprovalVO>> pending(@RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer size) {
        return Result.success(approvalService.pending(page, size));
    }

    /** 已处理记录（分页；只含当前审批人处理过的，按处理时间倒序） */
    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('APPROVER', 'ADMIN')")
    public Result<PageResult<ApprovalHistoryVO>> history(@RequestParam(required = false) Integer page,
                                                         @RequestParam(required = false) Integer size) {
        return Result.success(approvalService.history(page, size, UserContext.getUserId()));
    }

    /**
     * 审批通过（T3.2，docs/04 4.7）：含**复检**——待审批期间时段被抢 → 50001 + data.conflicts。
     * 重复审批（已被别人处理）→ 50002。
     */
    @PostMapping("/{meetingId}/pass")
    @PreAuthorize("hasAnyRole('APPROVER', 'ADMIN')")
    public Result<ApprovalResultVO> pass(@PathVariable Long meetingId,
                                         @Valid @RequestBody(required = false) ApprovalDTO dto) {
        String comment = dto == null ? null : dto.getComment();
        return Result.success(approvalService.pass(meetingId, comment, UserContext.getUserId()));
    }

    /** 审批拒绝（T3.3，docs/04 4.8）：理由必填（10001）→ 10 → 60 */
    @PostMapping("/{meetingId}/reject")
    @PreAuthorize("hasAnyRole('APPROVER', 'ADMIN')")
    public Result<ApprovalResultVO> reject(@PathVariable Long meetingId,
                                           @Valid @RequestBody(required = false) ApprovalDTO dto) {
        String comment = dto == null ? null : dto.getComment();
        return Result.success(approvalService.reject(meetingId, comment, UserContext.getUserId()));
    }
}
