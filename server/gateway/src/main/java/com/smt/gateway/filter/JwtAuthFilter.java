package com.smt.gateway.filter;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smt.common.result.ErrorCode;
import com.smt.common.result.Result;
import com.smt.common.util.JwtUtil;
import com.smt.gateway.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关鉴权过滤器（全局）：
 * ① 剥离外部传入的 X-User-*（防伪造：信任边界在网关，见 docs/04 接口设计 7.3）
 * ② 白名单放行（登录/刷新/actuator）
 * ③ 校验 JWT → 解析 userId/role → 重新注入 X-User-Id / X-User-Role
 * ④ 失败统一返回 401 + {code:10002}
 */
@Slf4j
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_ROLE = "X-User-Role";
    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthProperties authProperties;
    private final ObjectMapper objectMapper;
    private final JwtUtil jwtUtil;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public JwtAuthFilter(AuthProperties authProperties, ObjectMapper objectMapper) {
        this.authProperties = authProperties;
        this.objectMapper = objectMapper;
        this.jwtUtil = new JwtUtil(authProperties.getJwt().getSecret());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // ① 剥离外部伪造的身份头（无论是否白名单，统一先剥）
        ServerHttpRequest stripped = request.mutate()
                .headers(headers -> {
                    headers.remove(HEADER_USER_ID);
                    headers.remove(HEADER_USER_ROLE);
                })
                .build();

        // ② 白名单放行
        if (isWhitelisted(path)) {
            return chain.filter(exchange.mutate().request(stripped).build());
        }

        // ③ 校验 JWT 并注入身份头
        String token = resolveToken(request);
        if (StrUtil.isBlank(token)) {
            return unauthorized(exchange, "缺少 token");
        }
        try {
            Claims claims = jwtUtil.parse(token);
            ServerHttpRequest.Builder builder = stripped.mutate()
                    .header(HEADER_USER_ID, String.valueOf(jwtUtil.getUserId(claims)));
            String role = jwtUtil.getRole(claims);
            if (StrUtil.isNotBlank(role)) {
                builder.header(HEADER_USER_ROLE, role);
            }
            return chain.filter(exchange.mutate().request(builder.build()).build());
        } catch (JwtException | IllegalArgumentException e) {
            return unauthorized(exchange, e.getMessage());
        }
    }

    private boolean isWhitelisted(String path) {
        return authProperties.getWhitelist().stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private String resolveToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        return StrUtil.isNotBlank(header) && header.startsWith(BEARER_PREFIX)
                ? header.substring(BEARER_PREFIX.length()) : null;
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String reason) {
        log.warn("鉴权失败: path={}, reason={}", exchange.getRequest().getURI().getPath(), reason);
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] body = objectMapper.writeValueAsBytes(Result.error(ErrorCode.UNAUTHORIZED));
            DataBuffer buffer = response.bufferFactory().wrap(body);
            return response.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            return response.setComplete();
        }
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
