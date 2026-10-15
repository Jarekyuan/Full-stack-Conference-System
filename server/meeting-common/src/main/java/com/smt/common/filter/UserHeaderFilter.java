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
import java.util.Arrays;
import java.util.List;

/**
 * 读取网关注入的 X-User-Id / X-User-Roles → 写入 UserContext + 桥接 SecurityContext
 * （供 @PreAuthorize("hasRole('ADMIN')") 使用；多角色 = 多个 ROLE_ 权限对象，任一匹配即通过）。
 * 服务内信任请求头：外部伪造的同名头已由网关剥离后重写（docs/04 接口设计 1.4）。
 */
public class UserHeaderFilter extends OncePerRequestFilter {

    public static final String HEADER_USER_ID = "X-User-Id";
    /** 角色列表头（逗号分隔，如 "ADMIN,APPROVER"；v1.3 起替代原单值 X-User-Role） */
    public static final String HEADER_USER_ROLES = "X-User-Roles";

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
            LoginUser loginUser = new LoginUser(Long.valueOf(userId), parseRoles(request.getHeader(HEADER_USER_ROLES)));
            UserContext.set(loginUser);
            bindSecurityContext(loginUser);
        } catch (NumberFormatException ignored) {
            // 非法头值：正常链路不会出现（网关已保证格式），忽略身份由后续鉴权拦截
        }
    }

    /** "ADMIN,APPROVER" → ["ADMIN","APPROVER"]（空段与空白容错） */
    private List<String> parseRoles(String header) {
        if (StrUtil.isBlank(header)) {
            return List.of();
        }
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .toList();
    }

    private void bindSecurityContext(LoginUser loginUser) {
        if (loginUser.roles().isEmpty()) {
            return;
        }
        List<SimpleGrantedAuthority> authorities = loginUser.roles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                loginUser, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
