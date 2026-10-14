package com.smt.auth.controller;

import com.smt.auth.dto.UserBatchDTO;
import com.smt.auth.service.UserService;
import com.smt.auth.vo.UserBriefVO;
import com.smt.common.result.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 内部接口（服务间调用专用，路径 /internal/**）
 *
 * 隔离原理（评审 P1-1 修复）：网关路由只覆盖 /api/**，因此外部请求无法经网关到达本控制器；
 * 服务间通过 Nacos 服务名直连（Feign），不经过网关。配合"业务服务端口仅内网可达"的部署约定形成双层保护。
 *
 * 原实现是 UserController 的 /users/batch，与 /users/** 同前缀且无鉴权，
 * 任意登录用户可借网关路由批量拉取用户 realName+email（信息枚举）。
 */
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class InternalUserController {

    private final UserService userService;

    /** 批量查询用户精简信息（供 meeting-service 的 AuthFeignClient 调用） */
    @PostMapping("/batch")
    public Result<List<UserBriefVO>> batch(@Valid @RequestBody UserBatchDTO dto) {
        return Result.success(userService.batchByIds(dto.getIds()));
    }
}
