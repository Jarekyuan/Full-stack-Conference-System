/**
 * 结构化 AI 纪要：摘要 / 关键结论 / 决策 / 待办（表格）/ 风险与分歧。
 * - summary 为 null 时区分"生成失败"与"尚未生成/生成中"，不渲染空壳表格；
 * - 后端可能返回历史脏数据（priority 越界、字段缺失），数组一律 ?? [] 兜底；
 *   未知优先级回退 default 色，不认识的字段按空值展示。
 */
import { Alert, Empty, List, Table, Tag, Typography } from 'antd'
import type { MinutesSummary as Summary } from '@/api/minutes'

interface Props {
  summary: Summary | null
  aiStatus: number
  aiError: string | null
}

const PRIORITY_COLOR: Record<string, string> = {
  HIGH: 'red',
  MEDIUM: 'orange',
  LOW: 'blue',
}

export default function MinutesSummary({ summary, aiStatus, aiError }: Props) {
  if (!summary) {
    if (aiStatus === 40) return <Alert type="error" showIcon message={aiError ?? 'AI 纪要生成失败'} />
    return <Empty description={aiStatus === 20 ? '生成中…' : '尚未生成 AI 纪要'} />
  }
  const keyPoints = summary.keyPoints ?? []
  const decisions = summary.decisions ?? []
  const actionItems = summary.actionItems ?? []
  const risks = summary.risks ?? []
  return (
    <div className="space-y-4">
      <Typography.Paragraph>{summary.summary || '-'}</Typography.Paragraph>
      <section>
        <Typography.Title level={5}>关键结论</Typography.Title>
        <List
          size="small"
          dataSource={keyPoints}
          renderItem={(item) => <List.Item>· {item}</List.Item>}
        />
      </section>
      <section>
        <Typography.Title level={5}>决策</Typography.Title>
        <List
          size="small"
          dataSource={decisions}
          renderItem={(item) => (
            <List.Item>
              <div>
                <div>{item.content || '-'}</div>
                {item.context && (
                  <Typography.Text type="secondary" className="text-xs">
                    {item.context}
                  </Typography.Text>
                )}
              </div>
            </List.Item>
          )}
        />
      </section>
      <section>
        <Typography.Title level={5}>待办</Typography.Title>
        <Table
          size="small"
          rowKey={(row) => `${row.task}-${row.owner ?? ''}-${row.dueDate ?? ''}`}
          pagination={false}
          dataSource={actionItems}
          columns={[
            { title: '任务', dataIndex: 'task', render: (v) => v || '-' },
            { title: '负责人', dataIndex: 'owner', render: (v) => v ?? '-' },
            { title: '截止时间', dataIndex: 'dueDate', render: (v) => v ?? '-' },
            {
              title: '优先级',
              dataIndex: 'priority',
              render: (v) =>
                v ? <Tag color={PRIORITY_COLOR[v] ?? 'default'}>{v}</Tag> : '-',
            },
          ]}
        />
      </section>
      <section>
        <Typography.Title level={5}>风险与分歧</Typography.Title>
        {risks.length === 0 ? (
          <Typography.Text type="secondary">无</Typography.Text>
        ) : (
          <List
            size="small"
            dataSource={risks}
            renderItem={(item) => <List.Item>· {item}</List.Item>}
          />
        )}
      </section>
    </div>
  )
}
