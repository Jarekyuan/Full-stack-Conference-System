package com.smt.meeting.feign;

import com.smt.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * auth-service 调用客户端（服务发现直连，不经网关）
 * 走 /internal/** 内部路径（网关不路由该前缀，外部不可达，见 auth-service 的 InternalUserController）
 * 降级见 {@link AuthFeignFallbackFactory}：认证域故障返回空结果，不阻断预约主流程
 */
@FeignClient(name = "auth-service", fallbackFactory = AuthFeignFallbackFactory.class)
public interface AuthFeignClient {

    /** 批量查询用户精简信息（对接 auth-service POST /internal/users/batch） */
    @PostMapping("/internal/users/batch")
    Result<List<UserBriefVO>> batchUsers(@RequestBody UserBatchDTO dto);
}
