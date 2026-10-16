package com.smt.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 全链路 traceId（docs/04 1.3 契约：网关生成 `X-Trace-Id` 透传，响应头原样返回）。
 *
 * <p>职责：无头则生成、有头则沿用（上游代理/前端重放可续用同一条链路），
 * 请求头透传给下游服务、响应头回带给浏览器（前端 `ApiError.traceId` 已经读它，排障时能对上日志）。
 *
 * <p>顺序必须早于鉴权（{@link JwtAuthFilter} 是 -100）：
 * 401 这类**被网关拦下、根本到不了业务服务**的响应，也要带上 traceId——否则最需要排障的那类失败反而没线索。
 *
 * <p>M4（T4.9）在本过滤器之上再补 MDC/logback 那一半（服务侧日志打 `[traceId]`）；此处先把**接口契约**闭环。
 */
@Slf4j
@Component
public class TraceIdFilter implements GlobalFilter, Ordered {

    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    /** 上游传进来的 traceId 也当作不可信输入：只接受 8~64 位的安全字符，其余一律重新生成（防日志/响应头注入） */
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER_TRACE_ID);
        String traceId = incoming != null && SAFE_TRACE_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        exchange.getResponse().getHeaders().set(HEADER_TRACE_ID, traceId);

        ServerHttpRequest request = exchange.getRequest().mutate()
                .header(HEADER_TRACE_ID, traceId)
                .build();

        // 访问日志（T4.9）：网关侧记一行，业务服务侧也记一行，两边**同一个 traceId**——
        // 这才叫"全链路可串联"（在此之前网关只在 401 时打日志，
        // 正常请求在网关侧没有任何痕迹，"2+ 服务 traceId 一致"根本无处可验）。
        long start = System.currentTimeMillis();
        return chain.filter(exchange.mutate().request(request).build())
                .then(Mono.fromRunnable(() -> {
                    var status = exchange.getResponse().getStatusCode();
                    log.info("[traceId:{}] {} {} → {} ({}ms)", traceId,
                            exchange.getRequest().getMethod(),
                            exchange.getRequest().getURI().getRawPath(),
                            status == null ? "-" : status.value(),
                            System.currentTimeMillis() - start);
                }));
    }

    @Override
    public int getOrder() {
        // 必须最靠前：任何一条出口（含 401/异常兜底）都要能带上 traceId
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
