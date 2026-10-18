package com.smt.meeting.controller;

import com.smt.common.context.UserContext;
import com.smt.common.result.Result;
import com.smt.meeting.dto.MultipartCompleteDTO;
import com.smt.meeting.dto.MultipartInitDTO;
import com.smt.meeting.service.MultipartUploadService;
import com.smt.meeting.vo.FileCompleteVO;
import com.smt.meeting.vo.MultipartInitVO;
import com.smt.meeting.vo.MultipartPartVO;
import com.smt.meeting.vo.MultipartSessionVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会议纪要音频分片上传（对外路径 /api/files/multipart/**，网关剥离 /api）。
 *
 * <p>身份来自网关注入的 X-User-Id（UserContext），会话归属校验在 Service 内完成。</p>
 */
@RestController
@RequestMapping("/files/multipart")
@RequiredArgsConstructor
public class FileController {

    private final MultipartUploadService uploadService;

    @PostMapping("/init")
    public Result<MultipartInitVO> init(@Valid @RequestBody MultipartInitDTO dto) {
        return Result.success(uploadService.init(dto, UserContext.getUserId()));
    }

    @GetMapping("/{uploadId}/parts")
    public Result<List<MultipartPartVO>> parts(@PathVariable String uploadId,
                                               @RequestParam List<Integer> partNumbers) {
        return Result.success(uploadService.partUrls(
                uploadId, partNumbers, UserContext.getUserId()));
    }

    @GetMapping("/{uploadId}")
    public Result<MultipartSessionVO> session(@PathVariable String uploadId) {
        return Result.success(uploadService.session(uploadId, UserContext.getUserId()));
    }

    @PostMapping("/{uploadId}/complete")
    public Result<FileCompleteVO> complete(@PathVariable String uploadId,
                                           @Valid @RequestBody MultipartCompleteDTO dto) {
        return Result.success(uploadService.complete(
                uploadId, dto, UserContext.getUserId()));
    }

    @DeleteMapping("/{uploadId}")
    public Result<Void> abort(@PathVariable String uploadId) {
        uploadService.abort(uploadId, UserContext.getUserId());
        return Result.success();
    }
}
