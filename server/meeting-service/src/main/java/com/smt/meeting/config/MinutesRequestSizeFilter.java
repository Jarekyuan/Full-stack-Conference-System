package com.smt.meeting.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 转写导入的请求体上限：5MiB 文本 + JSON 转义余量，超过 8MiB 直接 70005。
 * 不依赖 Tomcat 的 form-post 上限（它不覆盖 JSON 请求体）。
 */
@Component
public class MinutesRequestSizeFilter extends OncePerRequestFilter {

    private static final long MAX_BYTES = 8L * 1024 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        if (request.getRequestURI().matches(".*/meetings/\\d+/minutes/import")
                && request.getContentLengthLong() > MAX_BYTES) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"code\":70005,\"msg\":\"文件或转写文本超出大小上限\",\"data\":null}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
