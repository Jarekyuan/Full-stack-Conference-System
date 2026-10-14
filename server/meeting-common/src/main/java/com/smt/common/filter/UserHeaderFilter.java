package com.smt.common.filter;

import cn.hutool.core.util.StrUtil;
import com.smt.common.context.LoginUser;
import com.smt.common.context.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 读取网关注入的 X-User-Id / X-User-Role → 写入 UserContext + 桥接 SecurityContext
 * （供 @PreAuthorize("hasRole('ADMIN')") 使用）。
 * 服务内信任请求头：外部伪造的同名头已由网关剥离后重写（docs/04 接口设计 1.4）。
 */
public class UserHeaderFilter extends OncePerRequestFilter {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_ROLE = "X-User-Role";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            resolveIdentity(request);
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private void resolveIdentity(HttpServletRequest request) {
        String userId = request.getHeader(HEADER_USER_ID);
        if (StrUtil.isBlank(userId)) {
            return;
        }
        try {
            LoginUser loginUser = new LoginUser(Long.valueOf(userId), request.getHeader(HEADER_USER_ROLE));
            UserContext.set(loginUser);
            bindSecurityContext(loginUser);
        } catch (NumberFormatException ignored) {
            // 非法头值：正常链路不会出现（网关已保证格式），忽略身份由后续鉴权拦截
        }
    }

    private void bindSecurityContext(LoginUser loginUser) {
        if (StrUtil.isBlank(loginUser.role())) {
            return;
        }
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                loginUser, null, List.of(new SimpleGrantedAuthority("ROLE_" + loginUser.role())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
