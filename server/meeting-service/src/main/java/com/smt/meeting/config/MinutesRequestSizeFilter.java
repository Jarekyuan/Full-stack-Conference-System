package com.smt.meeting.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smt.common.result.ErrorCode;
import com.smt.common.result.Result;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 转写导入的请求体上限：5MiB 文本 + JSON 转义余量，超过 8MiB 直接 70005。
 *
 * <p>路径用 {@link UrlPathHelper#getLookupPathForRequest(HttpServletRequest)} 解析再匹配：
 * 该方法会解码 URL（{@code %31} → {@code 1}）并去掉矩阵参数（{@code ;x=y}），
 * 避免编码/矩阵路径绕过限制。未知长度（chunked，{@code Content-Length < 0}）同样拒绝——
 * 本端点客户端始终发送 Content-Length，拒绝未知长度比缓冲整个请求体更安全。</p>
 */
@Component
@RequiredArgsConstructor
public class MinutesRequestSizeFilter extends OncePerRequestFilter {

    private static final long MAX_BYTES = 8L * 1024 * 1024;

    /**
     * 与 Spring 的 {@code @PathVariable Long} 转换口径对齐：允许前后空白与正负号，
     * 否则 {@code /meetings/+1/...}、{@code /meetings/%201%20/...}、{@code /meetings/-1/...}
     * 都会进入控制器与 {@code @RequestBody}，却绕过这里的大小限制。
     */
    private static final Pattern IMPORT_PATH =
            Pattern.compile(".*/meetings/\\s*[+-]?\\d+\\s*/minutes/import/?");

    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String path = UrlPathHelper.defaultInstance.getLookupPathForRequest(request);
        long contentLength = request.getContentLengthLong();
        if (IMPORT_PATH.matcher(path).matches()
                && (contentLength < 0 || contentLength > MAX_BYTES)) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json;charset=UTF-8");
            objectMapper.writeValue(response.getWriter(),
                    Result.error(ErrorCode.FILE_TOO_LARGE));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
