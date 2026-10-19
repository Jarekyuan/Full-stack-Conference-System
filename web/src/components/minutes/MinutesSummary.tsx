/**
 * 结构化 AI 纪要：摘要 / 关键结论 / 决策 / 待办（表格）/ 风险与分歧。
 * - summary 为 null 时按 AI 状态区分排队/生成中/失败/未生成，不渲染空壳表格；
 * - 40 的详细错误由 MinutesAiBar 的 Alert 负责，这里只给空态，避免重复报警；
 * - 后端可能返回历史脏数据（数组含 null、priority 越界、字段缺失），先 compactList 过滤，
 *   未知优先级回退 default 色，空章节统一显示“无”。
 */
import { Empty, List, Table, Tag, Typography } from 'antd'
import type { MinutesSummary as Summary } from '@/api/minutes'
import { compactList, minutesActionItemKey } from './minutesUi'

interface Props {
  summary: Summary | null
  aiStatus: number
}

const PRIORITY_COLOR: Record<string, string> = {
  HIGH: 'red',
  MEDIUM: 'orange',
  LOW: 'blue',
}

function EmptyText() {
  return <Typography.Text type="secondary">无</Typography.Text>
}

export default function MinutesSummary({ summary, aiStatus }: Props) {
  if (!summary) {
    if (aiStatus === 10) return <Empty description="排队等待生成…" />
    if (aiStatus === 20) return <Empty description="生成中…" />
    if (aiStatus === 40) {
      // 详细错误由 MinutesAiBar 的 Alert 展示，这里只给通用空态，避免重复报错
      return <Empty description="纪要生成失败，请查看上方错误信息" />
    }
    return <Empty description="尚未生成 AI 纪要" />
  }
  const keyPoints = compactList(summary.keyPoints)
  const decisions = compactList(summary.decisions)
  const actionItems = compactList(summary.actionItems)
  const risks = compactList(summary.risks)
  return (
    <div className="space-y-4">
      <Typography.Paragraph data-testid="minutes-summary">
        {summary.summary?.trim() || '-'}
      </Typography.Paragraph>
      <section>
        <Typography.Title level={5}>关键结论</Typography.Title>
        {keyPoints.length === 0 ? (
          <EmptyText />
        ) : (
          <List
            size="small"
            dataSource={keyPoints}
            renderItem={(item) => <List.Item>· {item}</List.Item>}
          />
        )}
      </section>
      <section>
        <Typography.Title level={5}>决策</Typography.Title>
        {decisions.length === 0 ? (
          <EmptyText />
        ) : (
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
        )}
      </section>
      <section>
        <Typography.Title level={5}>待办</Typography.Title>
        {actionItems.length === 0 ? (
          <EmptyText />
        ) : (
          <Table
            size="small"
            rowKey={(row, index) => minutesActionItemKey(row, index)}
            pagination={false}
            scroll={{ x: 'max-content' }}
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
        )}
      </section>
      <section>
        <Typography.Title level={5}>风险与分歧</Typography.Title>
        {risks.length === 0 ? (
          <EmptyText />
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
