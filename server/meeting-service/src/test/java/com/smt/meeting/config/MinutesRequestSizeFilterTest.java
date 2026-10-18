package com.smt.meeting.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smt.common.result.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 导入请求体上限：Content-Length 上限、未知长度拒绝、编码/矩阵路径归一化。
 */
class MinutesRequestSizeFilterTest {

    private static final int EIGHT_MIB = 8 * 1024 * 1024;
    private static final int NINE_MIB = 9 * 1024 * 1024;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MinutesRequestSizeFilter filter = new MinutesRequestSizeFilter(objectMapper);

    /** contentLength < 0 表示不设置 content（Mock 返回 -1，模拟 chunked/未知长度）。 */
    private MockHttpServletRequest request(String uri, int contentLength) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        if (contentLength >= 0) {
            request.setContent(new byte[contentLength]);
        }
        return request;
    }

    private int codeOf(MockHttpServletResponse response) throws Exception {
        JsonNode body = objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        return body.get("code").asInt();
    }

    private void assertRejected(String uri) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request(uri, NINE_MIB), response, chain);

        assertThat(codeOf(response)).isEqualTo(ErrorCode.FILE_TOO_LARGE.getCode());
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void rejectsOversizedImportBody() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/meetings/1/minutes/import", NINE_MIB), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(codeOf(response)).isEqualTo(ErrorCode.FILE_TOO_LARGE.getCode());
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void allowsExactLimit() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/meetings/1/minutes/import", EIGHT_MIB), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void rejectsUnknownContentLength() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/meetings/1/minutes/import", -1), response, chain);

        assertThat(codeOf(response)).isEqualTo(ErrorCode.FILE_TOO_LARGE.getCode());
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void rejectsPercentEncodedPath() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/meetings/%31/minutes/import", NINE_MIB), response, chain);

        assertThat(codeOf(response)).isEqualTo(ErrorCode.FILE_TOO_LARGE.getCode());
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void rejectsMatrixParameterPath() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/meetings/1/minutes/import;x=y", NINE_MIB), response, chain);

        assertThat(codeOf(response)).isEqualTo(ErrorCode.FILE_TOO_LARGE.getCode());
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void rejectsPlusSignedId() throws Exception {
        assertRejected("/meetings/+1/minutes/import");
    }

    @Test
    void rejectsWhitespacePaddedId() throws Exception {
        assertRejected("/meetings/%201%20/minutes/import");
    }

    @Test
    void rejectsNegativeId() throws Exception {
        assertRejected("/meetings/-1/minutes/import");
    }

    @Test
    void passesNonImportPathUnchanged() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = request("/meetings/1/minutes/transcript", NINE_MIB);

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getContentAsString()).isEmpty();
    }
}
