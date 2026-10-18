/** 会议纪要接口（docs/04 10.2；字段名与后端 VO 严格一致） */
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
  aiStatus: number
  aiError: string | null
  aiModel: string | null
  summary: MinutesSummary | null
  speakers: MinutesSpeaker[]
  insights: MinutesInsights
  permissions: { canManage: boolean; canView: boolean }
  createdAt: string
  updatedAt: string
}

export interface MinutesImportInput {
  fileId: number
  transcriptText: string
  durationMs?: number
  source?: 'TENCENT_ASR'
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

export function importMinutes(
  meetingId: number,
  input: MinutesImportInput,
): Promise<MinutesDetail> {
  return http.post<MinutesDetail>(`/meetings/${meetingId}/minutes/import`, input)
}

export function renameSpeaker(
  meetingId: number,
  speakerKey: string,
  input: { displayName: string; color: string },
): Promise<MinutesSpeaker> {
  return http.put<MinutesSpeaker>(
    `/meetings/${meetingId}/minutes/speakers/${encodeURIComponent(speakerKey)}`,
    input,
  )
}

export function generateMinutes(meetingId: number): Promise<{ aiStatus: number }> {
  return http.post<{ aiStatus: number }>(`/meetings/${meetingId}/minutes/generate`)
}

export function exportMinutes(meetingId: number, filename: string): Promise<void> {
  return downloadFile(`/meetings/${meetingId}/minutes/export?format=docx`, filename)
}
