/**
 * 纪要上传器：音频（m4a 为主）+ 腾讯 ASR 转写文本。
 * 音频走 useMultipartUpload；导入失败时 70003 的 data 带行号，直接提示到行。
 */
import { useRef, useState } from 'react'
import { Alert, App, Button, Card, Input, Progress, Space, Tag, Typography } from 'antd'
import { importMinutes } from '@/api/minutes'
import { ApiError } from '@/api/types'
import { useMultipartUpload } from '@/hooks/useMultipartUpload'

const AUDIO_ACCEPT = '.m4a,.mp3,.wav,.aac,.ogg,audio/*'

async function readAudioDuration(file: File): Promise<number | undefined> {
  return new Promise((resolve) => {
    const url = URL.createObjectURL(file)
    const audio = new Audio()
    const finish = (value?: number) => {
      URL.revokeObjectURL(url)
      resolve(value)
    }
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
  const [importing, setImporting] = useState(false)
  const { upload, pause, resume, progress, status, error } = useMultipartUpload(meetingId)

  const handleTranscriptFile = async (file: File) => {
    if (file.size > 5 * 1024 * 1024) {
      message.error('转写文本不能超过 5MiB')
      return
    }
    setTranscript(await file.text())
  }

  const handleSubmit = async () => {
    if (!audioFile) {
      message.warning('请选择音频文件')
      return
    }
    if (!transcript.trim()) {
      message.warning('请粘贴或上传转写文本')
      return
    }
    setImporting(true)
    try {
      const durationMs = await readAudioDuration(audioFile)
      const fileId = await upload(audioFile)
      if (fileId == null) return
      await importMinutes(meetingId, {
        fileId,
        transcriptText: transcript,
        durationMs,
        source: 'TENCENT_ASR',
      })
      message.success('会议纪要已导入')
      onImported()
    } catch (e) {
      if (e instanceof ApiError && e.code === 70003) {
        const detail = e.data as { line?: number; reason?: string } | null
        message.error(`第 ${detail?.line ?? '?'} 行：${detail?.reason ?? e.message}`)
      } else if (e instanceof ApiError) {
        message.error(e.message)
      } else if (!(e instanceof Error) || e.name !== 'AbortError') {
        message.error('导入失败，请稍后重试')
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
        message="上传会议录音与腾讯云 ASR 转写文本"
        description="音频仅支持 m4a/mp3/wav/aac/ogg，单文件不超过 2GiB；转写格式：[开始,结束,说话人] 文本。"
      />

      <Card size="small" title="1. 音频文件">
        <input
          ref={audioInput}
          type="file"
          accept={AUDIO_ACCEPT}
          hidden
          onChange={(e) => setAudioFile(e.target.files?.[0] ?? null)}
        />
        <Space wrap>
          <Button onClick={() => audioInput.current?.click()}>选择音频</Button>
          {audioFile && (
            <Tag color="blue">
              {audioFile.name}（{(audioFile.size / 1024 / 1024).toFixed(1)} MB）
            </Tag>
          )}
        </Space>
        {status === 'uploading' && (
          <div className="mt-3">
            <Progress percent={progress} status="active" />
            <Button size="small" className="mt-2" onClick={pause}>
              暂停上传
            </Button>
          </div>
        )}
        {status === 'paused' && (
          <div className="mt-3">
            <Progress percent={progress} status="exception" />
            <Button size="small" className="mt-2" onClick={() => resume()}>
              继续上传
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

      <Card size="small" title="2. 转写文本">
        <input
          ref={transcriptInput}
          type="file"
          accept=".txt,text/plain"
          hidden
          onChange={(e) => {
            const file = e.target.files?.[0]
            if (file) void handleTranscriptFile(file)
          }}
        />
        <Space className="mb-2">
          <Button onClick={() => transcriptInput.current?.click()}>上传 .txt</Button>
          <Typography.Text type="secondary">或直接粘贴下面的文本</Typography.Text>
        </Space>
        <Input.TextArea
          rows={8}
          value={transcript}
          onChange={(e) => setTranscript(e.target.value)}
          placeholder="[0:1.030,0:8.570,0]  恩格斯论述了雅典国家……"
        />
      </Card>

      <Button
        type="primary"
        loading={importing || status === 'uploading'}
        onClick={handleSubmit}
      >
        开始解析并进入工作台
      </Button>
    </Space>
  )
}
