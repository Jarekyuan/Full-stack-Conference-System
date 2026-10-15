package com.smt.meeting.feign;

import com.smt.common.result.ErrorCode;
import com.smt.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * auth-service 降级工厂：
 * 认证域是"非核心依赖"（列表渲染缺姓名也可展示 id），故障时返回**错误码结果**（不抛异常），
 * 不阻断预约主流程（架构原则见 docs/02 3.1 #4"非核心依赖不拖死核心链路"）。
 *
 * <p>为什么不是 `Result.success(List.of())`（评审 P1-16 改）：空列表会把
 * "**auth 挂了**" 与 "**这批 id 查无此人**" 压成同一个返回值，调用方无法区分，
 * 运维侧也永远看不到告警。现在返回 `code=10007`，调用方判 code 即可分开处理，
 * 两者都按 id 兜底渲染，但降级要打 WARN 日志/计数。
 */
@Slf4j
@Component
public class AuthFeignFallbackFactory implements FallbackFactory<AuthFeignClient> {

    @Override
    public AuthFeignClient create(Throwable cause) {
        return dto -> {
            log.warn("[Fallback] auth-service 调用失败（降级，调用方按 id 兜底渲染）: ids={}, cause={}",
                    dto == null ? null : dto.ids(), cause.toString());
            return Result.error(ErrorCode.SYSTEM_BUSY.getCode(), "用户信息暂不可用（auth-service 降级）");
        };
    }
}
