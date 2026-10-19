package com.smt.meeting.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 腾讯云录音文件识别（ASR）配置。
 *
 * <p>ASR 与 COS 可以复用同一组 CAM 子账号密钥；若权限分开，建议使用独立的
 * {@code TENCENT_ASR_SECRET_ID/KEY}。录音通过 COS 预签名 URL 交给 ASR 拉取，
 * 不需要把音频经过 meeting-service。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "minutes.asr")
public class AsrProperties {

    private Boolean enabled = true;
    /** tencent | mock */
    private String provider = "tencent";
    private String secretId = "";
    @ToString.Exclude
    private String secretKey = "";
    /** 与 COS 桶同地域优先，例如 ap-chengdu；不支持时改 ap-guangzhou */
    private String region = "ap-chengdu";
    private String endpoint = "asr.tencentcloudapi.com";
    /** 会议场景默认 16k_zh；电话录音用 8k_zh */
    private String engineModelType = "16k_zh";
    private Integer channelNum = 1;
    /** 0 = 旧版 [开始,结束,说话人] 行（与体验页一致）；3 = 按标点分段 + 词级时间戳 */
    private Integer resTextFormat = 0;
    private Integer speakerDiarization = 1;
    private Integer speakerNumber = 0;
    private Integer pollIntervalMs = 5000;
    private Integer maxAttempts = 3;
    /** 超过该时间仍停在 20，按失败处理并重试 */
    private Integer stuckTimeoutMinutes = 120;
    /** 交给 ASR 拉取音频的预签名 URL 有效期 */
    private Integer urlTtlHours = 6;
    private Integer batchSize = 5;
    /** mock provider 生成的句子数；E2E 可用 300 验证虚拟滚动 */
    private Integer mockSegments = 2;
}
