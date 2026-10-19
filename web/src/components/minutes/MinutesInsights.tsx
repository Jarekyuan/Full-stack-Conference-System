/**
 * 会议洞察：时长/覆盖率/发言人数指标卡 + 各发言人时长横向柱状图 + 发言人明细卡。
 * - ECharts 按需引入（core + Bar/Grid/Tooltip/Canvas），与 Dashboard 同一套做法；
 * - 不引 ECharts 主题包，坐标轴/悬浮框颜色按 useResolvedTheme 显式切换，背景透明；
 * - 图表实例负责 init/resize/dispose，组件卸载不 dispose 会泄漏 canvas；
 * - 无发言人数据时只显示空态（hooks 必须在提前 return 前全部调用）。
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

echarts.use([BarChart, GridComponent, TooltipComponent, CanvasRenderer])

function Chart({ option }: { option: EChartsCoreOption }) {
  const ref = useRef<HTMLDivElement>(null)
  const instance = useRef<echarts.ECharts | null>(null)
  useEffect(() => {
    if (!ref.current) return
    instance.current = echarts.init(ref.current)
    const onResize = () => instance.current?.resize()
    window.addEventListener('resize', onResize)
    return () => {
      window.removeEventListener('resize', onResize)
      instance.current?.dispose()
      instance.current = null
    }
  }, [])
  useEffect(() => {
    instance.current?.setOption(option, true)
  }, [option])
  return <div ref={ref} style={{ width: '100%', height: 320 }} />
}

function formatDuration(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  return `${Math.floor(total / 60)} 分 ${String(total % 60).padStart(2, '0')} 秒`
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
  const sorted = useMemo(
    () => [...insights.speakers].sort((a, b) => b.speakingMs - a.speakingMs),
    [insights.speakers],
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
            ? `${speaker.displayName}<br/>发言 ${formatDuration(speaker.speakingMs)}<br/>占比 ${(speaker.talkRatio * 100).toFixed(1)}%`
            : ''
        },
      },
      grid: { left: 80, right: 24, top: 16, bottom: 32 },
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
  if (insights.speakers.length === 0) return <Empty description="暂无洞察数据" />
  return (
    <div className="space-y-4">
      <Row gutter={16}>
        <Col span={6}>
          <Card size="small">
            <Statistic title="录音时长" value={formatDuration(insights.durationMs)} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <Statistic title="总发言时长" value={formatDuration(insights.speakingMs)} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <Statistic
              title="发言覆盖率"
              value={Math.round(insights.speechCoverage * 100)}
              suffix="%"
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <Statistic title="发言人数" value={insights.speakers.length} />
          </Card>
        </Col>
      </Row>
      <Card size="small" title="各发言人时长">
        <Chart option={option} />
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
