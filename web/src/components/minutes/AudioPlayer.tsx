/**
 * 纪要音频播放器：播放/暂停、±15 秒、进度拖动、倍速与音量。
 * - `<audio>` 元素由父组件持有 ref（转写列表点击定位也复用同一个元素）；
 * - 播放进度以毫秒回调给父组件（onTimeUpdate），用于高亮当前转写分段；
 * - 换源时重置播放状态；时长非法（NaN/Infinity）时禁用进度条与快进；
 * - 音频加载/播放失败交给父组件（onError）统一提示。
 */
import { useEffect, useState, type RefObject } from 'react'
import { Button, Select, Slider, Space, Typography } from 'antd'
import { Pause, Play, RotateCcw, RotateCw } from 'lucide-react'
import { formatOffset } from '@/utils/transcriptSync'

interface Props {
  url: string
  audioRef: RefObject<HTMLAudioElement>
  onTimeUpdate: (ms: number) => void
  onError: () => void
}

export default function AudioPlayer({ url, audioRef, onTimeUpdate, onError }: Props) {
  const [playing, setPlaying] = useState(false)
  const [current, setCurrent] = useState(0)
  const [duration, setDuration] = useState(0)
  const [rate, setRate] = useState(1)
  const [volume, setVolume] = useState(1)

  // 换源后媒体元素会重新加载，旧的播放状态/进度/时长不再有效
  useEffect(() => {
    setPlaying(false)
    setCurrent(0)
    setDuration(0)
  }, [url])

  useEffect(() => {
    const audio = audioRef.current
    if (audio) audio.playbackRate = rate
  }, [audioRef, rate])

  useEffect(() => {
    const audio = audioRef.current
    if (audio) audio.volume = volume
  }, [audioRef, volume])

  const syncDuration = (audio: HTMLAudioElement) => {
    const d = audio.duration
    const valid = Number.isFinite(d) && d > 0
    setDuration(valid ? d : 0)
  }

  const durationValid = Number.isFinite(duration) && duration > 0

  const toggle = () => {
    const audio = audioRef.current
    if (!audio) return
    if (audio.paused) {
      audio.play().catch(() => onError())
    } else {
      audio.pause()
    }
  }

  const rewind = () => {
    const audio = audioRef.current
    if (audio) audio.currentTime = Math.max(0, audio.currentTime - 15)
  }

  const forward = () => {
    const audio = audioRef.current
    if (!audio || !durationValid) return
    audio.currentTime = Math.min(duration, audio.currentTime + 15)
  }

  return (
    <div>
      <audio
        ref={audioRef}
        src={url}
        onTimeUpdate={(e) => {
          const ms = Math.round(e.currentTarget.currentTime * 1000)
          setCurrent(ms / 1000)
          onTimeUpdate(ms)
        }}
        onLoadedMetadata={(e) => syncDuration(e.currentTarget)}
        onDurationChange={(e) => syncDuration(e.currentTarget)}
        onEmptied={() => {
          setPlaying(false)
          setCurrent(0)
          setDuration(0)
        }}
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        onEnded={() => setPlaying(false)}
        onError={onError}
        className="hidden"
      />
      <Space wrap size="middle">
        <Button
          aria-label="快退 15 秒"
          icon={<RotateCcw size={16} />}
          onClick={rewind}
        />
        <Button
          type="primary"
          shape="circle"
          aria-label={playing ? '暂停' : '播放'}
          icon={playing ? <Pause size={18} /> : <Play size={18} />}
          onClick={toggle}
        />
        <Button
          aria-label="快进 15 秒"
          icon={<RotateCw size={16} />}
          onClick={forward}
        />
        <Typography.Text type="secondary">{formatOffset(current * 1000)}</Typography.Text>
        <Slider
          ariaLabelForHandle="播放进度"
          className="min-w-[220px]"
          min={0}
          max={duration || 1}
          step={0.1}
          value={current}
          disabled={!durationValid}
          onChange={(value) => {
            const audio = audioRef.current
            if (audio) {
              audio.currentTime = value
              setCurrent(value)
            }
          }}
          tooltip={{ formatter: null }}
        />
        <Typography.Text type="secondary">{formatOffset(duration * 1000)}</Typography.Text>
        <Select
          aria-label="播放速度"
          size="small"
          value={rate}
          onChange={setRate}
          options={[0.5, 1, 1.25, 1.5, 2].map((v) => ({ value: v, label: `${v}x` }))}
          style={{ width: 80 }}
        />
        <Slider
          ariaLabelForHandle="音量"
          className="w-24"
          min={0}
          max={1}
          step={0.05}
          value={volume}
          onChange={setVolume}
          tooltip={{ formatter: null }}
        />
      </Space>
    </div>
  )
}
