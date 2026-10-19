/**
 * 会议纪要接口（设计文档 §10.2；字段名与后端 VO 严格一致。
 * 真相源：docs/superpowers/specs/2026-10-18-meeting-minutes-design.md。
 * docs/04 的接口章节待 Task 19 补登）
 */
import { downloadFile, http } from './request'

export interface MinutesSpeaker {
  speakerKey: string
  displayName: string
  color: string
}

export interface TranscriptSegment {
  id: number
  seq: number
  speakerKey: string
  startMs: number
  endMs: number
  text: string
}

export interface SpeakerInsight {
  speakerKey: string
  displayName: string
  color: string
  speakingMs: number
  talkRatio: number
  segmentCount: number
  longestSegmentMs: number
}

export interface MinutesInsights {
  durationMs: number
  speakingMs: number
  speechCoverage: number
  totalSegments: number
  speakers: SpeakerInsight[]
}

export interface MinutesSummary {
  summary: string
  keyPoints: string[]
  decisions: { content: string; context: string | null }[]
  actionItems: {
    task: string
    owner: string | null
    dueDate: string | null
    /**
     * 后端 MinutesSummaryParser 只放行 HIGH/MEDIUM/LOW；历史脏数据可能越界，
     * Task 15 的渲染代码仍需对未知值兜底（显示原文或默认级别）。
     */
    priority: 'HIGH' | 'MEDIUM' | 'LOW' | null
  }[]
  risks: string[]
}

export interface MinutesDetail {
  id: number
  meetingId: number
  audio: {
    fileId: number
    fileName: string
    sizeBytes: number
    durationMs: number
  } | null
  transcriptSource: string
  /** ASR 状态：0 未转写 / 10 待识别 / 20 识别中 / 30 成功 / 40 失败 */
  asrStatus: number
  asrError: string | null
  aiStatus: number
  aiError: string | null
  /** 当前 AI 供应商：deepseek / mock */
  aiProvider: string
  aiModel: string | null
  summary: MinutesSummary | null
  speakers: MinutesSpeaker[]
  insights: MinutesInsights
  permissions: { canManage: boolean; canView: boolean }
  createdAt: string
  updatedAt: string
}

export function getMinutes(meetingId: number): Promise<MinutesDetail | null> {
  return http.get<MinutesDetail | null>(`/meetings/${meetingId}/minutes`)
}

export function getTranscript(meetingId: number): Promise<{ segments: TranscriptSegment[] }> {
  return http.get<{ segments: TranscriptSegment[] }>(`/meetings/${meetingId}/minutes/transcript`)
}

export function getAudioUrl(meetingId: number): Promise<{ url: string; expiresAt: string }> {
  return http.get<{ url: string; expiresAt: string }>(
    `/meetings/${meetingId}/minutes/audio-url`,
  )
}

/** 音频上传完成后发起腾讯 ASR 转写；异步状态通过纪要详情的 asrStatus 轮询 */
export function startTranscription(
  meetingId: number,
  fileId: number,
): Promise<MinutesDetail> {
  return http.post<MinutesDetail>(
    `/meetings/${meetingId}/minutes/transcribe`,
    { fileId },
    { timeout: 30_000 },
  )
}

export function generateMinutes(meetingId: number): Promise<{ aiStatus: number }> {
  return http.post<{ aiStatus: number }>(`/meetings/${meetingId}/minutes/generate`)
}

/** 导出 Word；优先使用服务端 Content-Disposition 文件名，filename 仅作兜底 */
export function exportMinutes(meetingId: number, filename?: string): Promise<void> {
  return downloadFile(`/meetings/${meetingId}/minutes/export?format=docx`, filename)
}
