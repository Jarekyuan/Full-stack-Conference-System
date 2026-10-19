/**
 * AI 纪要状态条：展示生成状态（0/10/20/30/40）+ 触发/重试按钮 + 内联提示。
 * - 10/20 为队列中/生成中，按钮禁用避免重复提交（面板本身还在轮询刷新）；
 * - 40 时展示后端 aiError；只有 canManage 才渲染生成按钮。
 */
import { Alert, Button, Space, Tag } from 'antd'
import { Sparkles } from 'lucide-react'

interface Props {
  aiStatus: number
  aiError: string | null
  canManage: boolean
  loading: boolean
  onGenerate: () => void
}

const STATUS: Record<number, { text: string; color: string }> = {
  0: { text: '未生成', color: 'default' },
  10: { text: '待生成', color: 'blue' },
  20: { text: '生成中', color: 'processing' },
  30: { text: '已生成', color: 'green' },
  40: { text: '生成失败', color: 'red' },
}

export default function MinutesAiBar({
  aiStatus,
  aiError,
  canManage,
  loading,
  onGenerate,
}: Props) {
  const status = STATUS[aiStatus] ?? { text: String(aiStatus), color: 'default' }
  const busy = aiStatus === 10 || aiStatus === 20
  return (
    <Space direction="vertical" className="w-full">
      <Space>
        <Tag color={status.color}>AI {status.text}</Tag>
        {canManage && (
          <Button
            size="small"
            type="primary"
            icon={<Sparkles size={14} />}
            loading={loading}
            disabled={busy}
            onClick={onGenerate}
          >
            {aiStatus === 40 ? '重试生成' : aiStatus === 30 ? '重新生成' : '生成纪要'}
          </Button>
        )}
      </Space>
      {aiStatus === 40 && aiError && <Alert type="error" showIcon message={aiError} />}
      {busy && (
        <Alert type="info" showIcon message="AI 正在生成，通常需要几十秒；失败会自动重试。" />
      )}
    </Space>
  )
}
