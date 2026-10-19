/**
 * 会议洞察：时长/覆盖率/发言人数指标卡 + 各发言人时长横向柱状图 + 发言人明细卡。
 * - ECharts 按需引入（core + Bar/Grid/Tooltip/Canvas），与 Dashboard 同一套做法；
 * - tooltip 走 HTML 渲染，displayName 是用户可编辑内容 → 必须 escapeHtml 防 XSS；
 * - 不引 ECharts 主题包，坐标轴/悬浮框颜色按 useResolvedTheme 显式切换，背景透明；
 * - 图表实例负责 init/dispose，并用 ResizeObserver 跟随容器尺寸（切侧栏/缩放窗口）；
 * - speakers 每次轮询都是新数组，按内容签名缓存排序结果，避免图表反复 setOption。
 */
import { useEffect, useMemo, useRef } from 'react'
import { Card, Col, Empty, Row, Statistic, Typography } from 'antd'
import * as echarts from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsCoreOption } from 'echarts/core'
import type { MinutesInsights as Insights } from '@/api/minutes'
import { useResolvedTheme } from '@/stores/themeStore'
import { compactList, escapeHtml, formatDuration } from './minutesUi'

echarts.use([BarChart, GridComponent, TooltipComponent, CanvasRenderer])

function Chart({
  option,
  height = 320,
  label,
}: {
  option: EChartsCoreOption
  height?: number
  label: string
}) {
  const ref = useRef<HTMLDivElement>(null)
  const instance = useRef<echarts.ECharts | null>(null)
  useEffect(() => {
    const element = ref.current
    if (!element) return
    instance.current = echarts.init(element)
    const observer = new ResizeObserver(() => instance.current?.resize())
    observer.observe(element)
    return () => {
      observer.disconnect()
      instance.current?.dispose()
      instance.current = null
    }
  }, [])
  useEffect(() => {
    instance.current?.setOption(option, true)
  }, [option])
  return <div ref={ref} role="img" aria-label={label} style={{ width: '100%', height }} />
}

export default function MinutesInsights({ insights }: { insights: Insights }) {
  const dark = useResolvedTheme() === 'dark'
  const colors = useMemo(
    () => ({
      axis: dark ? '#cbd5e1' : '#6b7280',
      split: dark ? '#334155' : '#e5e7eb',
      tooltipBg: dark ? '#1e293b' : '#ffffff',
      tooltipBorder: dark ? '#334155' : '#e5e7eb',
      tooltipText: dark ? '#e5e7eb' : '#111827',
    }),
    [dark],
  )
  const speakerSignature = insights.speakers
    .map((s) =>
      [s.speakerKey, s.displayName, s.color, s.speakingMs, s.talkRatio, s.segmentCount].join(
        '\u0000',
      ),
    )
    .join('\u0001')
  const sorted = useMemo(
    () => compactList(insights.speakers).sort((a, b) => b.speakingMs - a.speakingMs),
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 轮询每次返回新数组，按内容签名缓存
    [speakerSignature],
  )
  const option = useMemo<EChartsCoreOption>(
    () => ({
      backgroundColor: 'transparent',
      tooltip: {
        trigger: 'axis',
        backgroundColor: colors.tooltipBg,
        borderColor: colors.tooltipBorder,
        textStyle: { color: colors.tooltipText },
        formatter: (params: unknown) => {
          const item = (params as { dataIndex: number }[])[0]
          const speaker = sorted[item.dataIndex]
          return speaker
            ? `${escapeHtml(speaker.displayName)}<br/>发言 ${formatDuration(speaker.speakingMs)}<br/>占比 ${(speaker.talkRatio * 100).toFixed(1)}%`
            : ''
        },
      },
      grid: { left: 12, right: 24, top: 16, bottom: 32, containLabel: true },
      xAxis: {
        type: 'value',
        axisLabel: {
          color: colors.axis,
          formatter: (value: number) => `${Math.round(value / 60000)}m`,
        },
        splitLine: { lineStyle: { color: colors.split } },
      },
      yAxis: {
        type: 'category',
        data: sorted.map((s) => s.displayName),
        axisLabel: { color: colors.axis },
        axisLine: { lineStyle: { color: colors.split } },
      },
      series: [
        {
          type: 'bar',
          data: sorted.map((s) => ({ value: s.speakingMs, itemStyle: { color: s.color } })),
          barMaxWidth: 24,
        },
      ],
    }),
    [colors, sorted],
  )
  if (sorted.length === 0) return <Empty description="暂无洞察数据" />
  return (
    <div className="space-y-4">
      <Row gutter={16}>
        <Col xs={12} md={6}>
          <Card size="small">
            <Statistic title="录音时长" value={formatDuration(insights.durationMs)} />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card size="small">
            <Statistic title="总发言时长" value={formatDuration(insights.speakingMs)} />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card size="small">
            <Statistic
              title="发言覆盖率"
              value={Math.round(insights.speechCoverage * 100)}
              suffix="%"
            />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card size="small">
            <Statistic title="发言人数" value={sorted.length} />
          </Card>
        </Col>
      </Row>
      <Card size="small" title="各发言人时长">
        <Chart
          option={option}
          height={Math.min(800, Math.max(320, sorted.length * 36))}
          label="各发言人发言时长柱状图"
        />
      </Card>
      <Row gutter={[12, 12]}>
        {sorted.map((speaker) => (
          <Col key={speaker.speakerKey} xs={24} sm={12} lg={8}>
            <Card size="small">
              <div className="flex items-center gap-2">
                <span
                  className="inline-block h-3 w-3 rounded-full"
                  style={{ backgroundColor: speaker.color }}
                />
                <Typography.Text strong>{speaker.displayName}</Typography.Text>
              </div>
              <div className="mt-2 text-sm text-slate-600 dark:text-slate-300">
                {formatDuration(speaker.speakingMs)} · {(speaker.talkRatio * 100).toFixed(1)}% ·{' '}
                {speaker.segmentCount} 段
              </div>
            </Card>
          </Col>
        ))}
      </Row>
    </div>
  )
}
