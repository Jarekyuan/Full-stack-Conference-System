/**
 * 发言人重命名弹窗：修改显示名并挑选标识色。
 * - 打开时以传入 speaker 初始化表单；空白名称不允许提交；
 * - 保存期间按钮 loading，提交失败由父组件提示，弹窗保持打开以便重试。
 */
import { useEffect, useState } from 'react'
import { Input, Modal, Select, Space } from 'antd'
import type { MinutesSpeaker } from '@/api/minutes'

const COLORS = [
  '#7D8CFF', '#69CFF5', '#FFB020', '#52C41A',
  '#FF6D6A', '#B37FEB', '#13C2C2', '#FA8C16',
]

interface Props {
  open: boolean
  speaker: MinutesSpeaker | null
  onClose: () => void
  onSubmit: (displayName: string, color: string) => Promise<void>
}

export default function SpeakerRenameModal({ open, speaker, onClose, onSubmit }: Props) {
  const [name, setName] = useState('')
  const [color, setColor] = useState(COLORS[0])
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (open && speaker) {
      setName(speaker.displayName)
      setColor(speaker.color)
    }
  }, [open, speaker])

  const handleOk = async () => {
    if (!name.trim()) return
    setSaving(true)
    try {
      await onSubmit(name.trim(), color)
    } catch {
      // 失败提示由父组件负责；这里吞掉 rejection，避免 Modal 暴露未处理的 Promise
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      title="修改发言人"
      open={open}
      onCancel={onClose}
      onOk={handleOk}
      okText="保存"
      confirmLoading={saving}
      okButtonProps={{ disabled: !name.trim() }}
    >
      <Space direction="vertical" className="w-full">
        <Input
          aria-label="发言人姓名"
          value={name}
          onChange={(e) => setName(e.target.value)}
          maxLength={50}
          placeholder="发言人姓名"
        />
        <Select
          aria-label="发言人颜色"
          value={color}
          onChange={setColor}
          options={COLORS.map((value) => ({
            value,
            label: (
              <span className="inline-flex items-center gap-2">
                <span
                  className="inline-block h-3 w-3 rounded-full"
                  style={{ backgroundColor: value }}
                />
                {value}
              </span>
            ),
          }))}
        />
      </Space>
    </Modal>
  )
}
