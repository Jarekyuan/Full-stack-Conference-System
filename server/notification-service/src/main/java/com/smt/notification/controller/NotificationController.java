package com.smt.notification.controller;

import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.smt.common.context.UserContext;
import com.smt.common.result.PageResult;
import com.smt.common.result.Result;
import com.smt.notification.dto.NotificationDeleteDTO;
import com.smt.notification.service.NotificationService;
import com.smt.notification.vo.NotificationVO;

/**
 * 通知接口（对外路径 /api/notifications/**，docs/04 3.3）。
 *
 * <p>**收件人一律取网关注入的身份**（`UserContext` ← `X-User-Id`），接口上不接受任何 userId 参数——
 * 一旦允许客户端传 userId，就等于开放"任意读别人的站内信"。
 * 所以这里没有 `@PreAuthorize`：不是"不校验权限"，而是"权限就是本人"，
 * 由「网关剥离外部身份头 → 验签 → 重新注入」这条链路保证（docs/04 7.3）。
 */
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /** 我的通知列表（分页，时间倒序） */
    @GetMapping
    public Result<PageResult<NotificationVO>> list(@RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer size) {
        return Result.success(notificationService.page(UserContext.getUserId(), page, size));
    }

    /** 未读数（顶栏红点） */
    @GetMapping("/unread-count")
    public Result<Map<String, Long>> unreadCount() {
        return Result.success(Map.of("unreadCount", notificationService.unreadCount(UserContext.getUserId())));
    }

    /** 单条已读（幂等；别人的/不存在的 → 60001，前端静默） */
    @PostMapping("/{id}/read")
    public Result<Void> read(@PathVariable Long id) {
        notificationService.markRead(id, UserContext.getUserId());
        return Result.success();
    }

    /** 全部已读；返回实际更新条数 */
    @PostMapping("/read-all")
    public Result<Map<String, Integer>> readAll() {
        return Result.success(Map.of("updated", notificationService.markAllRead(UserContext.getUserId())));
    }

    /** 删除单条（不是我的/不存在 → 60001） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        notificationService.delete(id, UserContext.getUserId());
        return Result.success();
    }

    /** 批量删除（只删自己的；返回实际删除条数） */
    @PostMapping("/delete")
    public Result<Map<String, Integer>> deleteBatch(@Valid @RequestBody NotificationDeleteDTO dto) {
        return Result.success(Map.of("deleted",
                notificationService.deleteBatch(UserContext.getUserId(), dto.getIds())));
    }

    /** 删除我的全部通知（返回实际删除条数） */
    @DeleteMapping
    public Result<Map<String, Integer>> deleteAll() {
        return Result.success(Map.of("deleted", notificationService.deleteAll(UserContext.getUserId())));
    }
}
