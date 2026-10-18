package com.smt.meeting.controller;

import com.smt.common.context.UserContext;
import com.smt.common.result.Result;
import com.smt.meeting.dto.MinutesImportDTO;
import com.smt.meeting.dto.SpeakerUpdateDTO;
import com.smt.meeting.service.MinutesAiService;
import com.smt.meeting.service.MinutesService;
import com.smt.meeting.vo.AiGenerateVO;
import com.smt.meeting.vo.AudioUrlVO;
import com.smt.meeting.vo.MinutesDetailVO;
import com.smt.meeting.vo.MinutesSpeakerVO;
import com.smt.meeting.vo.TranscriptVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会议纪要接口（对外路径 /api/meetings/{id}/minutes/**，网关剥离 /api）。
 * 身份来自网关注入的 X-User-Id（UserContext），数据权限在 Service 内校验。
 */
@RestController
@RequestMapping("/meetings")
@RequiredArgsConstructor
public class MinutesController {

    private final MinutesService minutesService;
    private final MinutesAiService minutesAiService;

    @GetMapping("/{id}/minutes")
    public Result<MinutesDetailVO> detail(@PathVariable Long id) {
        return Result.success(minutesService.detail(id, UserContext.getUserId()));
    }

    @GetMapping("/{id}/minutes/transcript")
    public Result<TranscriptVO> transcript(@PathVariable Long id) {
        return Result.success(minutesService.transcript(id, UserContext.getUserId()));
    }

    @GetMapping("/{id}/minutes/audio-url")
    public Result<AudioUrlVO> audioUrl(@PathVariable Long id) {
        return Result.success(minutesService.audioUrl(id, UserContext.getUserId()));
    }

    @PostMapping("/{id}/minutes/import")
    public Result<MinutesDetailVO> importMinutes(@PathVariable Long id,
                                                 @Valid @RequestBody MinutesImportDTO dto) {
        Long userId = UserContext.getUserId();
        minutesService.importMinutes(id, dto, userId);
        return Result.success(minutesService.detail(id, userId));
    }

    @PutMapping("/{id}/minutes/speakers/{speakerKey}")
    public Result<MinutesSpeakerVO> renameSpeaker(@PathVariable Long id,
                                                  @PathVariable String speakerKey,
                                                  @Valid @RequestBody SpeakerUpdateDTO dto) {
        return Result.success(minutesService.renameSpeaker(
                id, speakerKey, dto, UserContext.getUserId()));
    }

    /** 请求异步生成结构化纪要：返回当前 AI 状态（10 待生成 / 20 生成中）。 */
    @PostMapping("/{id}/minutes/generate")
    public Result<AiGenerateVO> generate(@PathVariable Long id) {
        return Result.success(minutesAiService.request(id, UserContext.getUserId()));
    }
}
