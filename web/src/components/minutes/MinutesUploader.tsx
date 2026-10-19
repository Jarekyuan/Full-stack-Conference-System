/**
 * 纪要上传器：只上传会议录音，随后自动调用腾讯 ASR 转写。
 * - 音频走 useMultipartUpload，支持暂停/续传；
 * - 上传完成拿到 fileId 后调 /minutes/transcribe，父组件刷新详情即可看到识别进度；
 * - 转写失败重试时可复用已上传的 fileId，不重复传大文件。
 */
import { useRef, useState } from 'react'
import { Alert, App, Button, Card, Progress, Space, Tag } from 'antd'
import { startTranscription } from '@/api/minutes'
import { useMultipartUpload } from '@/hooks/useMultipartUpload'
import { describeMinutesError } from './minutesUi'

const AUDIO_ACCEPT = '.m4a,.mp3,.wav,.aac,.ogg,audio/*'

interface UploadedAudio {
  /** name:size:lastModified，文件内容/身份变化时缓存失效 */
  key: string
  fileId: number
}

function audioFileKey(file: File): string {
  return `${file.name}:${file.size}:${file.lastModified}`
}

interface Props {
  meetingId: number
  /** 转写任务已提交：父组件刷新纪要详情，展示 ASR 进度/工作台 */
  onImported: () => void
}

export default function MinutesUploader({ meetingId, onImported }: Props) {
  const { message } = App.useApp()
  const audioInput = useRef<HTMLInputElement>(null)
  const [audioFile, setAudioFile] = useState<File | null>(null)
  const [submitting, setSubmitting] = useState(false)
  // ref 给异步流程读最新值，state 只负责渲染"已上传/待转写"标签
  const uploadedRef = useRef<UploadedAudio | null>(null)
  const [uploaded, setUploaded] = useState<UploadedAudio | null>(null)
  const { upload, pause, resume, reset, progress, status, error } = useMultipartUpload(meetingId)

  const selectionLocked = submitting || status === 'uploading' || status === 'paused'
  const uploadedForCurrentFile =
    uploaded != null && audioFile != null && uploaded.key === audioFileKey(audioFile)
  const uploadStatusText =
    status === 'uploading'
      ? '音频上传中'
      : status === 'paused'
        ? `音频上传已暂停，已完成 ${progress}%`
        : status === 'error'
          ? '音频上传失败'
          : uploadedForCurrentFile
            ? '音频已上传，待转写'
            : audioFile
              ? `已选择音频 ${audioFile.name}`
              : ''

  const rememberUploaded = (value: UploadedAudio | null) => {
    uploadedRef.current = value
    setUploaded(value)
  }

  const handleAudioChange = (file: File | null) => {
    setAudioFile(file)
    // 换文件后旧 fileId 不可复用；key 已含 size/lastModified，这里仍显式清缓存
    rememberUploaded(null)
  }

  const finishTranscription = async (fileId: number) => {
    await startTranscription(meetingId, fileId)
    message.success('音频已上传，正在识别转写')
    onImported()
  }

  /** 70006 统一收口：清缓存 + 重置 hook（含 localStorage 会话），提示重新上传 */
  const handleExpiredSession = (errorMessage: string) => {
    rememberUploaded(null)
    reset()
    message.error(errorMessage)
  }

  const handleSubmit = async () => {
    if (submitting) return
    if (!audioFile) {
      message.warning('请选择会议录音')
      return
    }

    setSubmitting(true)
    try {
      const key = audioFileKey(audioFile)
      let fileId: number
      if (uploadedRef.current?.key === key) {
        // 上次已上传成功、仅转写提交失败：直接复用 fileId
        fileId = uploadedRef.current.fileId
      } else {
        const uploadedId = await upload(audioFile)
        if (uploadedId == null) return
        fileId = uploadedId
        rememberUploaded({ key, fileId })
      }
      await finishTranscription(fileId)
    } catch (e) {
      const info = describeMinutesError(e, '转写提交失败，请稍后重试')
      if (info.kind === 'upload-session-expired') {
        handleExpiredSession(info.message)
      } else if (info.kind !== 'abort') {
        message.error(info.message)
      }
    } finally {
      setSubmitting(false)
    }
  }

  /** 暂停后的续传：拿到 fileId 后直接提交转写，不再要求用户点第二次提交 */
  const handleResume = async () => {
    if (submitting || !audioFile) return
    setSubmitting(true)
    try {
      const fileId = await resume()
      if (fileId == null) return
      rememberUploaded({ key: audioFileKey(audioFile), fileId })
      await finishTranscription(fileId)
    } catch (e) {
      const info = describeMinutesError(e, '转写提交失败，请稍后重试')
      if (info.kind === 'upload-session-expired') {
        handleExpiredSession(info.message)
      } else if (info.kind !== 'abort') {
        message.error(info.message)
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Space direction="vertical" size="middle" className="w-full">
      <Alert
        type="info"
        showIcon
        message="上传会议录音，系统会自动转写并进入会议纪要工作台"
        description="支持 m4a/mp3/wav/aac/ogg，单文件不超过 2GiB；m4a 请使用 AAC 编码（ALAC 浏览器无法播放）。转写由腾讯云 ASR 完成，可能需要几分钟。"
      />

      <Card size="small" title="会议录音">
        <input
          ref={audioInput}
          type="file"
          accept={AUDIO_ACCEPT}
          hidden
          disabled={selectionLocked}
          onChange={(e) => {
            handleAudioChange(e.target.files?.[0] ?? null)
            e.target.value = ''
          }}
        />
        {/* 上传状态用 sr-only live region 播报；进度百分比频繁变化，不放进播报文本 */}
        <div className="sr-only" aria-live="polite">
          {uploadStatusText}
        </div>
        <Space wrap>
          <Button disabled={selectionLocked} onClick={() => audioInput.current?.click()}>
            选择音频
          </Button>
          {audioFile && (
            <Tag color="blue">
              {audioFile.name}（{(audioFile.size / 1024 / 1024).toFixed(1)} MB）
            </Tag>
          )}
          {uploadedForCurrentFile && <Tag color="green">音频已上传，待转写</Tag>}
        </Space>
        {status === 'uploading' && (
          <div className="mt-3">
            <Progress aria-label="音频上传进度" percent={progress} status="active" />
            <Button size="small" className="mt-2" onClick={pause}>
              暂停上传
            </Button>
          </div>
        )}
        {status === 'paused' && (
          <div className="mt-3">
            <Progress
              aria-label="音频上传进度"
              percent={progress}
              status="normal"
              format={() => `已暂停（已上传 ${progress}%）`}
            />
            <Button size="small" className="mt-2" onClick={() => void handleResume()}>
              继续上传并转写
            </Button>
          </div>
        )}
        {status === 'error' && (
          <Alert
            className="mt-3"
            type="error"
            showIcon
            message="音频上传失败"
            description={error ?? '请重试；已上传分片会保留'}
          />
        )}
      </Card>

      <Button
        type="primary"
        loading={submitting || status === 'uploading'}
        onClick={() => void handleSubmit()}
      >
        上传并开始转写
      </Button>
    </Space>
  )
}
