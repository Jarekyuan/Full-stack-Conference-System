package com.smt.meeting.controller;

import com.smt.common.context.LoginUser;
import com.smt.common.context.UserContext;
import com.smt.common.exception.BusinessException;
import com.smt.common.exception.GlobalExceptionHandler;
import com.smt.common.result.ErrorCode;
import com.smt.meeting.service.MinutesAiService;
import com.smt.meeting.service.MinutesExportService;
import com.smt.meeting.service.MinutesService;
import com.smt.meeting.vo.MinutesDetailVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.OutputStream;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
 * Task 10 修正：导出接口必须在设置 docx 响应头**之前**完成存在性/权限校验，
 * 否则 70001/60001 会被预设的 docx Content-Type 吞掉，客户端看不到错误码。
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
    void missingMinutesReturnsJsonErrorWithoutDocxHeaders() throws Exception {
        when(minutesService.detail(MEETING_ID, USER_ID)).thenReturn(null);

        mockMvc.perform(get("/meetings/{id}/minutes/export", MEETING_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(ErrorCode.MINUTES_NOT_FOUND.getCode()))
                .andExpect(header().doesNotExist("Content-Disposition"));

        verifyNoInteractions(minutesExportService);
    }

    @Test
    void permissionFailureReturnsJsonErrorWithoutDocxHeaders() throws Exception {
        when(minutesService.detail(MEETING_ID, USER_ID))
                .thenThrow(new BusinessException(ErrorCode.NO_PERMISSION));

        mockMvc.perform(get("/meetings/{id}/minutes/export", MEETING_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(ErrorCode.NO_PERMISSION.getCode()))
                .andExpect(header().doesNotExist("Content-Disposition"));

        verifyNoInteractions(minutesExportService);
    }

    @Test
    void nonDocxFormatReturnsParamErrorBeforePermissionCheck() throws Exception {
        mockMvc.perform(get("/meetings/{id}/minutes/export", MEETING_ID)
                        .param("format", "pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_ERROR.getCode()))
                .andExpect(header().doesNotExist("Content-Disposition"));

        verifyNoInteractions(minutesService, minutesExportService);
    }

    @Test
    void validRequestReturnsDocxHeadersAndCallsExport() throws Exception {
        when(minutesService.detail(MEETING_ID, USER_ID)).thenReturn(new MinutesDetailVO());
        when(minutesExportService.fileName(MEETING_ID))
                .thenReturn("会议纪要-周会-20261018.docx");

        mockMvc.perform(get("/meetings/{id}/minutes/export", MEETING_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentType(DOCX))
                .andExpect(header().string("Content-Disposition",
                        startsWith("attachment; filename*=UTF-8''")))
                .andExpect(header().string("Content-Disposition",
                        containsString("%E5%91%A8%E4%BC%9A")));

        verify(minutesExportService).export(eq(MEETING_ID), eq(USER_ID), any(OutputStream.class));
    }
}
