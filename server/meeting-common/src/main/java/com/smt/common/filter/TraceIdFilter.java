package com.smt.common.filter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 服务侧 traceId（T4.9）：把 `X-Trace-Id` 放进 MDC，让本服务的**每一行日志**都带上它。
 *
 * <p>网关已经生成/校验过这个头（见 gateway 的 TraceIdFilter），服务侧只做两件事：
 * ① 有头就沿用（**不重新生成**——否则同一个请求在网关日志和一个服务日志里会是两个 id，链路就断了）；
 * ② 没有头就自己生成一个。第二种情况是**直连业务端口排障**（不过网关）时发生的：
 * 那种请求也不该报错、更不该打出一堆没有 traceId 的日志（验收标准之一）。
 *
 * <p>顺序必须最靠前（{@link Ordered#HIGHEST_PRECEDENCE}）：安全链、鉴权失败、参数校验失败……
 * 这些"最早出问题"的日志恰恰最需要 traceId。
 *
 * <p>为什么要 `finally { MDC.remove(...) }`：Tomcat 线程是**复用**的。
 * 不清理的话，下一个请求（哪怕自己没带 traceId）会继承上一个请求的 traceId——
 * 日志里就会出现"一个 id 串起两个不相干请求"的假线索，比没有 traceId 更坑。
 * （这与 {@code UserContext} 的清理是同一个理由，见 UserHeaderFilter。）
 *
 * <p>⚠️ 这里**不往响应头写 traceId**：网关已经写了（它是权威来源），
 * 服务再写一遍会与网关那份在转发时合并成**两个同名响应头**，前端 `ApiError.traceId` 读到的东西就不确定了。
 * 直连服务时拿不到响应头里的 traceId 是无所谓的——排障看的是日志。
 */
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    /** MDC 的 key，与各服务 logback 的 `%X{traceId}` 对应 */
    public static final String MDC_KEY = "traceId";

    /** 与网关同一套白名单：8~64 位安全字符。校验放在这里是为了**直连场景**（没过网关）也不被注入 */
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    /** 供 Feign 拦截器取用：把同一个 traceId 带给下游服务（否则下游只能自己造一个新的，链路就断在这） */
    public static String currentTraceId() {
        return MDC.get(MDC_KEY);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER_TRACE_ID);
        String traceId = incoming != null && SAFE_TRACE_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put(MDC_KEY, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
