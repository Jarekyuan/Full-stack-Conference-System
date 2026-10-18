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
     * 按端点形状匹配，id 段不限定字符集：大小上限不依赖 id 的取值，若试图镜像
     * Spring {@code @PathVariable Long} 的转换规则（任意 Unicode 数字、任意位置空白、
     * 正负号等）反而会漏掉 {@code 1 2}、全角/阿拉伯-印度数字等能进入控制器的形式。
     * 这里只要 id 是"单个非斜杠段"即可；不存在/无权限的 id 由 Service 层处理。
     */
    private static final Pattern IMPORT_PATH =
            Pattern.compile(".*/meetings/[^/]+/minutes/import/?");

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
