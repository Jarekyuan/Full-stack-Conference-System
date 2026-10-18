/**
 * 分片上传接口（设计文档 §10.1；字段名与后端 VO 严格一致。
 * 真相源：docs/superpowers/specs/2026-10-18-meeting-minutes-design.md。
 * docs/04 的接口章节待 Task 19 补登）
 */
import { http } from './request'

export interface MultipartInitInput {
  bizType: 'MINUTES_AUDIO'
  bizId: number
  fileName: string
  fileSize: number
  contentType: string
}

export interface MultipartInit {
  uploadId: string
  objectKey: string
  chunkSize: number
  totalParts: number
  expiresAt: string
}

export interface MultipartPart {
  partNumber: number
  url: string
  expiresAt: string
}

export interface UploadedPart {
  partNumber: number
  etag: string
  size: number
}

export interface MultipartSession {
  uploadId: string
  objectKey: string
  chunkSize: number
  totalParts: number
  status: number
  uploadedParts: UploadedPart[]
  missingPartNumbers: number[]
  expiresAt: string
}

export interface FileComplete {
  fileId: number
  objectKey: string
  sizeBytes: number
}

export function initMultipart(input: MultipartInitInput): Promise<MultipartInit> {
  return http.post<MultipartInit>('/files/multipart/init', input)
}

export function getPartUrls(uploadId: string, partNumbers: number[]): Promise<MultipartPart[]> {
  return http.get<MultipartPart[]>(
    `/files/multipart/${encodeURIComponent(uploadId)}/parts`,
    {
      params: { partNumbers: partNumbers.join(',') },
    },
  )
}

export function getUploadSession(uploadId: string): Promise<MultipartSession> {
  return http.get<MultipartSession>(`/files/multipart/${encodeURIComponent(uploadId)}`)
}

export function completeMultipart(
  uploadId: string,
  parts: { partNumber: number; etag: string }[],
): Promise<FileComplete> {
  return http.post<FileComplete>(
    `/files/multipart/${encodeURIComponent(uploadId)}/complete`,
    { parts },
  )
}

export function abortMultipart(uploadId: string): Promise<void> {
  return http.delete<void>(`/files/multipart/${encodeURIComponent(uploadId)}`)
}
