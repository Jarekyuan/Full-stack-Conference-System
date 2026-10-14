package com.smt.meeting.feign;

import com.smt.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * auth-service 降级工厂：
 * 认证域是"非核心依赖"（列表渲染缺姓名也可展示 id），故障时返回空结果 + 告警日志，
 * 不阻断预约主流程（架构原则见 docs/02 3.1 #4"非核心依赖不拖死核心链路"）
 */
@Slf4j
@Component
public class AuthFeignFallbackFactory implements FallbackFactory<AuthFeignClient> {

    @Override
    public AuthFeignClient create(Throwable cause) {
        return dto -> {
            log.warn("[Fallback] auth-service 调用失败，降级返回空结果: {}", cause.getMessage());
            return Result.success(List.of());
        };
    }
}
