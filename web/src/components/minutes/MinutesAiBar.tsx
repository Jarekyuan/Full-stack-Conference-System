/**
 * AI 纪要状态条：展示生成状态（0/10/20/30/40）+ 触发/重试按钮 + 内联提示。
 * - 状态文案/颜色/按钮文案统一来自 minutesUi.MINUTES_AI_STATUS_META，避免与头部标签漂移；
 * - 10/20 为队列中/生成中，按钮禁用避免重复提交（面板本身还在轮询刷新）；
 * - 40 时展示后端 aiError；只有 canManage 才渲染生成按钮。
 */
import { Alert, Button, Space, Tag } from 'antd'
import { Sparkles } from 'lucide-react'
import { minutesAiStatusMeta } from './minutesUi'

interface Props {
  aiStatus: number
  aiError: string | null
  /** mock provider：未调用真实大模型，明确提示避免把演示数据当成生成异常 */
  demoMode: boolean
  canManage: boolean
  loading: boolean
  onGenerate: () => void
}

export default function MinutesAiBar({
  aiStatus,
  aiError,
  demoMode,
  canManage,
  loading,
  onGenerate,
}: Props) {
  const status = minutesAiStatusMeta(aiStatus)
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
            disabled={busy || loading}
            onClick={onGenerate}
          >
            {status.actionLabel}
          </Button>
        )}
      </Space>
      {demoMode && (
        <Alert
          type="warning"
          showIcon
          message="当前为本地演示模式（mock）"
          description="未调用真实大模型，纪要按照语音转写做抽取式生成；配置 DEEPSEEK_API_KEY 并切换 MINUTES_AI_PROVIDER=deepseek 后即可生成真实 AI 纪要。"
        />
      )}
      {aiStatus === 40 && aiError && <Alert type="error" showIcon message={aiError} />}
      {busy && (
        <Alert type="info" showIcon message="AI 正在生成，通常需要几十秒；失败会自动重试。" />
      )}
    </Space>
  )
}
