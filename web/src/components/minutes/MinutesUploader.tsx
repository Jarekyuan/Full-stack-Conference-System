/**
 * 纪要上传器：音频（m4a 为主）+ 腾讯 ASR 语音转写。
 * - 音频走 useMultipartUpload，支持暂停/续传；
 * - 上传成功即缓存 fileId：导入失败（如 70003 行格式错误）重试时只重导、不重传；
 * - 暂停后「继续上传并导入」在分片续传完成后直接导入；
 * - 语音转写以 ref 为准，上传期间继续编辑的内容会用于最终导入。
 */
import { useRef, useState } from 'react'
import { Alert, App, Button, Card, Input, Progress, Space, Tag, Typography } from 'antd'
import { importMinutes } from '@/api/minutes'
import { useMultipartUpload } from '@/hooks/useMultipartUpload'
import { describeMinutesError } from './minutesUi'

const AUDIO_ACCEPT = '.m4a,.mp3,.wav,.aac,.ogg,audio/*'
const TRANSCRIPT_MAX_BYTES = 5 * 1024 * 1024
/** 音频元数据读取上限：读不到就按 undefined 传给后端，不阻塞导入 */
const DURATION_TIMEOUT_MS = 5000

interface UploadedAudio {
  /** name:size:lastModified，文件内容/身份变化时缓存失效 */
  key: string
  fileId: number
}

function audioFileKey(file: File): string {
  return `${file.name}:${file.size}:${file.lastModified}`
}

async function readAudioDuration(file: File): Promise<number | undefined> {
  return new Promise((resolve) => {
    const url = URL.createObjectURL(file)
    const audio = new Audio()
    let settled = false
    const finish = (value?: number) => {
      if (settled) return
      settled = true
      window.clearTimeout(timer)
      audio.onloadedmetadata = null
      audio.onerror = null
      audio.removeAttribute('src')
      audio.load()
      URL.revokeObjectURL(url)
      resolve(value)
    }
    const timer = window.setTimeout(() => finish(undefined), DURATION_TIMEOUT_MS)
    audio.onloadedmetadata = () => {
      finish(Number.isFinite(audio.duration) ? Math.round(audio.duration * 1000) : undefined)
    }
    audio.onerror = () => finish(undefined)
    audio.src = url
  })
}

interface Props {
  meetingId: number
  onImported: () => void
}

export default function MinutesUploader({ meetingId, onImported }: Props) {
  const { message } = App.useApp()
  const audioInput = useRef<HTMLInputElement>(null)
  const transcriptInput = useRef<HTMLInputElement>(null)
  const [audioFile, setAudioFile] = useState<File | null>(null)
  const [transcript, setTranscript] = useState('')
  const transcriptRef = useRef('')
  const [importing, setImporting] = useState(false)
  // ref 给异步流程读最新值，state 只负责渲染"已上传/待导入"标签
  const uploadedRef = useRef<UploadedAudio | null>(null)
  const [uploaded, setUploaded] = useState<UploadedAudio | null>(null)
  const { upload, pause, resume, reset, progress, status, error } = useMultipartUpload(meetingId)

  const selectionLocked = importing || status === 'uploading' || status === 'paused'
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
            ? '音频已上传，待导入'
            : audioFile
              ? `已选择音频 ${audioFile.name}`
              : ''

  const setTranscriptValue = (value: string) => {
    transcriptRef.current = value
    setTranscript(value)
  }

  const rememberUploaded = (value: UploadedAudio | null) => {
    uploadedRef.current = value
    setUploaded(value)
  }

  const handleAudioChange = (file: File | null) => {
    setAudioFile(file)
    // 换文件后旧 fileId 不可复用；key 已含 size/lastModified，这里仍显式清缓存
    rememberUploaded(null)
  }

  const handleTranscriptFile = async (file: File) => {
    if (file.size > TRANSCRIPT_MAX_BYTES) {
      message.error('语音转写不能超过 5MiB')
      return
    }
    try {
      setTranscriptValue(await file.text())
    } catch {
      message.error('无法读取语音转写文件，请重试或直接粘贴')
    }
  }

  /** 只做导入（不含上传）；durationMs 此时读取，缓存 fileId 的纯重试也能带上时长 */
  const runImport = async (fileId: number, transcriptText: string) => {
    const durationMs = audioFile ? await readAudioDuration(audioFile) : undefined
    await importMinutes(meetingId, {
      fileId,
      transcriptText,
      durationMs,
      source: 'TENCENT_ASR',
    })
  }

  const finishImport = async (fileId: number) => {
    await runImport(fileId, transcriptRef.current)
    message.success('会议纪要已导入')
    rememberUploaded(null)
    onImported()
  }

  /** 70006 统一收口：清缓存 + 重置 hook（含 localStorage 会话），提示重新上传 */
  const handleExpiredSession = (errorMessage: string) => {
    rememberUploaded(null)
    reset()
    message.error(errorMessage)
  }

  const handleSubmit = async () => {
    if (importing) return
    if (!audioFile) {
      message.warning('请选择音频文件')
      return
    }
    if (!transcriptRef.current.trim()) {
      message.warning('请粘贴或上传语音转写')
      return
    }

    setImporting(true)
    let stage: 'upload' | 'import' = 'upload'
    try {
      const key = audioFileKey(audioFile)
      let fileId: number
      if (uploadedRef.current?.key === key) {
        // 上次已上传成功、仅导入失败：直接复用 fileId，避免大文件重传
        fileId = uploadedRef.current.fileId
      } else {
        const uploadedId = await upload(audioFile)
        if (uploadedId == null) return
        fileId = uploadedId
        rememberUploaded({ key, fileId })
      }
      stage = 'import'
      await finishImport(fileId)
    } catch (e) {
      const info = describeMinutesError(e, '导入失败，请稍后重试')
      if (info.kind === 'upload-session-expired') {
        handleExpiredSession(info.message)
      } else if (stage === 'import' && info.kind !== 'abort') {
        // 导入失败用 toast；上传失败已由 hook 的 error 状态在 Card 内联展示，避免重复提示
        message.error(info.message)
      }
    } finally {
      setImporting(false)
    }
  }

  /** 暂停后的续传：拿到 fileId 后直接导入，不再要求用户点第二次提交 */
  const handleResume = async () => {
    if (importing || !audioFile) return
    if (!transcriptRef.current.trim()) {
      message.warning('请粘贴或上传语音转写')
      return
    }

    setImporting(true)
    let stage: 'upload' | 'import' = 'upload'
    try {
      const fileId = await resume()
      if (fileId == null) return
      rememberUploaded({ key: audioFileKey(audioFile), fileId })
      stage = 'import'
      await finishImport(fileId)
    } catch (e) {
      const info = describeMinutesError(e, '导入失败，请稍后重试')
      if (info.kind === 'upload-session-expired') {
        handleExpiredSession(info.message)
      } else if (stage === 'import' && info.kind !== 'abort') {
        message.error(info.message)
      }
    } finally {
      setImporting(false)
    }
  }

  return (
    <Space direction="vertical" size="middle" className="w-full">
      <Alert
        type="info"
        showIcon
        message="上传会议录音与腾讯云 ASR 语音转写"
        description="音频仅支持 m4a/mp3/wav/aac/ogg，单文件不超过 2GiB；m4a 请使用 AAC 编码（ALAC 浏览器无法播放）。语音转写格式：[开始,结束,说话人] 文本。"
      />

      <Card size="small" title="1. 音频文件">
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
          {uploadedForCurrentFile && <Tag color="green">音频已上传，待导入</Tag>}
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
              继续上传并导入
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

      <Card size="small" title="2. 语音转写">
        <input
          ref={transcriptInput}
          type="file"
          accept=".txt,text/plain"
          hidden
          onChange={(e) => {
            const file = e.target.files?.[0]
            if (file) void handleTranscriptFile(file)
            e.target.value = ''
          }}
        />
        <Space className="mb-2">
          <Button onClick={() => transcriptInput.current?.click()}>上传 .txt</Button>
          <Typography.Text type="secondary">或直接粘贴下面的文本</Typography.Text>
        </Space>
        <Input.TextArea
          aria-label="语音转写"
          rows={8}
          value={transcript}
          onChange={(e) => setTranscriptValue(e.target.value)}
          placeholder="[0:1.030,0:8.570,0]  恩格斯论述了雅典国家……"
        />
      </Card>

      <Button
        type="primary"
        loading={importing || status === 'uploading'}
        onClick={() => void handleSubmit()}
      >
        开始解析并进入工作台
      </Button>
    </Space>
  )
}
