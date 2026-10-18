package com.smt.meeting.controller;

import com.smt.common.context.LoginUser;
import com.smt.common.context.UserContext;
import com.smt.common.exception.BusinessException;
import com.smt.common.exception.GlobalExceptionHandler;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.service.MinutesAiService;
import com.smt.meeting.service.MinutesExportService;
import com.smt.meeting.service.MinutesService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 10 修正：导出接口必须先完整构建文档，成功后才设置 docx 响应头。
 * 这样构建期间的 70001/10003/未预期异常都会以 JSON Result 返回，
 * 不会出现“响应头是 docx、响应体是错误 JSON”导致客户端读不到错误码的情况。
 */
class MinutesControllerExportTest {

    private static final Long MEETING_ID = 42L;
    private static final Long USER_ID = 4L;
    private static final String DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private MinutesService minutesService;
    private MinutesExportService minutesExportService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        minutesService = mock(MinutesService.class);
        minutesExportService = mock(MinutesExportService.class);
        MinutesController controller = new MinutesController(
                minutesService, mock(MinutesAiService.class), minutesExportService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        UserContext.set(new LoginUser(USER_ID, List.of("USER")));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void nonDocxFormatReturnsParamErrorBeforeBuild() throws Exception {
        mockMvc.perform(get("/meetings/{id}/minutes/export", MEETING_ID)
                        .param("format", "pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_ERROR.getCode()))
                .andExpect(header().doesNotExist("Content-Disposition"));

        verifyNoInteractions(minutesService, minutesExportService);
    }

    @Test
    void missingMinutesReturnsJsonErrorWithoutDocxHeaders() throws Exception {
        when(minutesExportService.build(MEETING_ID, USER_ID))
                .thenThrow(new BusinessException(ErrorCode.MINUTES_NOT_FOUND));

        mockMvc.perform(get("/meetings/{id}/minutes/export", MEETING_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(ErrorCode.MINUTES_NOT_FOUND.getCode()))
                .andExpect(header().doesNotExist("Content-Disposition"));
    }

    @Test
    void permissionFailureReturnsJsonErrorWithoutDocxHeaders() throws Exception {
        when(minutesExportService.build(MEETING_ID, USER_ID))
                .thenThrow(new BusinessException(ErrorCode.NO_PERMISSION));

        mockMvc.perform(get("/meetings/{id}/minutes/export", MEETING_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(ErrorCode.NO_PERMISSION.getCode()))
                .andExpect(header().doesNotExist("Content-Disposition"));
    }

    @Test
    void unexpectedBuildFailureReturnsJsonErrorWithoutDocxHeaders() throws Exception {
        when(minutesExportService.build(MEETING_ID, USER_ID))
                .thenThrow(new IllegalStateException("docx build failed"));

        mockMvc.perform(get("/meetings/{id}/minutes/export", MEETING_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(ErrorCode.SYSTEM_ERROR.getCode()))
                .andExpect(header().doesNotExist("Content-Disposition"));
    }

    @Test
    void validRequestReturnsDocxHeadersAndWritesBytes() throws Exception {
        byte[] bytes = new byte[]{1, 2, 3};
        when(minutesExportService.build(MEETING_ID, USER_ID))
                .thenReturn(new MinutesExportService.ExportDocument(bytes,
                        "会议纪要-周会-20261018.docx"));

        MvcResult result = mockMvc.perform(get("/meetings/{id}/minutes/export", MEETING_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentType(DOCX))
                .andExpect(header().string("Content-Disposition",
                        startsWith("attachment; filename*=UTF-8''")))
                .andExpect(header().string("Content-Disposition",
                        containsString("%E5%91%A8%E4%BC%9A")))
                .andExpect(content().bytes(bytes))
                .andReturn();

        assertThat(result.getResponse().getHeader("Content-Length"))
                .isEqualTo(String.valueOf(bytes.length));
        verify(minutesExportService).build(MEETING_ID, USER_ID);
        verifyNoInteractions(minutesService);
    }
}
