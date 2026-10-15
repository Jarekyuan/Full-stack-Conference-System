package com.smt.common.filter;

import com.smt.common.context.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserHeaderFilterTest {

    private final UserHeaderFilter filter = new UserHeaderFilter();

    @AfterEach
    void cleanup() {
        UserContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldPopulateContextWithinRequestAndClearAfter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(UserHeaderFilter.HEADER_USER_ID, "4");
        request.addHeader(UserHeaderFilter.HEADER_USER_ROLES, "USER");
        AtomicBoolean asserted = new AtomicBoolean(false);

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertEquals(4L, UserContext.getUserId());
            assertEquals(List.of("USER"), UserContext.getRoles());
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertNotNull(authentication);
            assertEquals("ROLE_USER", authentication.getAuthorities().iterator().next().getAuthority());
            asserted.set(true);
        });

        assertTrue(asserted.get());
        // 请求结束后必须清理（防线程池复用串数据）
        assertNull(UserContext.get());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void multiRolesShouldProduceMultipleAuthorities() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(UserHeaderFilter.HEADER_USER_ID, "1");
        // 含空格的逗号分隔（验证 trim 容错）；v1.3：admin 兼 ADMIN + APPROVER
        request.addHeader(UserHeaderFilter.HEADER_USER_ROLES, "ADMIN, APPROVER");
        AtomicBoolean asserted = new AtomicBoolean(false);

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertEquals(List.of("ADMIN", "APPROVER"), UserContext.getRoles());
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertNotNull(authentication);
            List<String> authorities = authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .toList();
            // 多角色 → 多个 ROLE_ 权限对象（@PreAuthorize hasRole 任一匹配即通过）
            assertTrue(authorities.contains("ROLE_ADMIN"));
            assertTrue(authorities.contains("ROLE_APPROVER"));
            asserted.set(true);
        });

        assertTrue(asserted.get());
    }

    @Test
    void withoutHeadersContextShouldStayEmpty() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> assertNull(UserContext.get()));
    }

    @Test
    void invalidUserIdHeaderShouldBeIgnored() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(UserHeaderFilter.HEADER_USER_ID, "not-a-number");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> assertNull(UserContext.get()));
    }
}
