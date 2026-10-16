/**
 * src/pages/admin/Dashboard.tsx
 * 统计看板（T5.4 / H-1 + H-2，口径见 docs/04 4.10）：会议室使用率柱状图 + 我的会议趋势折线图。
 *
 * 三个取舍：
 * ① **ECharts 按需引入**（`echarts/core` + 只注册用到的图表/组件）：`import echarts from 'echarts'` 会把
 *    整个图表库（含地图/3D 等用不到的东西）打进包；按需引入后只多 ~200KB，且是独立 chunk。
 * ② **数据全部来自接口**，页面上不重算口径（占用率后端按"区间裁剪 ÷ 可用分钟"算好；
 *    前端再算一遍就是两套口径，迟早对不上）。
 * ③ **用完就 dispose**：ECharts 实例挂在 DOM 上，组件卸载不销毁会在切页后泄漏（实例 + canvas）。
 */
import { useEffect, useMemo, useRef, useState } from 'react'
import { Card, DatePicker, Empty, Spin, Typography } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsCoreOption } from 'echarts/core'
import { fetchMyStats, fetchRoomUsage, type MyMeetingStats, type RoomUsage } from '@/api/stats'
import { useAppRequest } from '@/hooks/useAppRequest'

echarts.use([BarChart, LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const { RangePicker } = DatePicker
const { Text } = Typography

/** ECharts 的薄封装：负责 init / setOption / resize / dispose，业务只给 option */
function Chart({ option, height = 320 }: { option: EChartsCoreOption; height?: number }) {
  const ref = useRef<HTMLDivElement>(null)
  const instance = useRef<echarts.ECharts | null>(null)

  useEffect(() => {
    if (!ref.current) return
    instance.current = echarts.init(ref.current)
    const onResize = () => instance.current?.resize()
    window.addEventListener('resize', onResize)
    return () => {
      window.removeEventListener('resize', onResize)
      instance.current?.dispose() // 不 dispose 会在切页后泄漏实例与 canvas
      instance.current = null
    }
  }, [])

  useEffect(() => {
    instance.current?.setOption(option, true)
  }, [option])

  return <div ref={ref} style={{ width: '100%', height }} />
}

export default function Dashboard() {
  // 默认近 30 天（与后端缺省一致；显式传入是为了让"改区间重查"统一走同一条参数路径）
  const [range, setRange] = useState<[Dayjs, Dayjs]>([dayjs().subtract(29, 'day'), dayjs()])

  const usage = useAppRequest(
    () => fetchRoomUsage(range[0].format('YYYY-MM-DD'), range[1].format('YYYY-MM-DD')),
    { manual: false, refreshDeps: [range] },
  )
  const mine = useAppRequest(fetchMyStats, { manual: false })

  const usageOption = useMemo<EChartsCoreOption>(() => {
    const data: RoomUsage | undefined = usage.data
    return {
      tooltip: {
        trigger: 'axis',
        // 悬浮时把"原始值"也带上：只显示百分比的话没法判断"0.5% 是不是数据错了"
        formatter: (params: unknown) => {
          const p = (params as { dataIndex: number }[])[0]
          const item = data?.items[p.dataIndex]
          return item
            ? `${item.roomName}<br/>占用率 ${(item.usageRate * 100).toFixed(2)}%<br/>${item.meetingCount} 场 / ${item.totalMinutes} 分钟`
            : ''
        },
      },
      grid: { left: 48, right: 16, top: 32, bottom: 40 },
      xAxis: { type: 'category', data: (data?.items ?? []).map((i) => i.roomName) },
      yAxis: { type: 'value', name: '占用率(%)' },
      series: [
        {
          name: '占用率',
          type: 'bar',
          barMaxWidth: 48,
          // 接口给的是 0~1 小数，百分比只在**展示层**乘 100（量纲在 docs/04 4.10 写死）
          data: (data?.items ?? []).map((i) => Number((i.usageRate * 100).toFixed(2))),
        },
      ],
    }
  }, [usage.data])

  const mineOption = useMemo<EChartsCoreOption>(() => {
    const data: MyMeetingStats | undefined = mine.data
    return {
      tooltip: { trigger: 'axis' },
      legend: { data: ['会议数', '时长(分钟)'] },
      grid: { left: 48, right: 48, top: 40, bottom: 40 },
      xAxis: { type: 'category', data: (data?.daily ?? []).map((d) => d.date.slice(5)) },
      yAxis: [
        { type: 'value', name: '场次' },
        { type: 'value', name: '分钟' },
      ],
      series: [
        { name: '会议数', type: 'line', smooth: true, data: (data?.daily ?? []).map((d) => d.count) },
        {
          name: '时长(分钟)',
          type: 'line',
          smooth: true,
          yAxisIndex: 1,
          data: (data?.daily ?? []).map((d) => d.minutes),
        },
      ],
    }
  }, [mine.data])

  const summary = usage.data
    ? `本区间共 ${usage.data.items.reduce((s, i) => s + i.meetingCount, 0)} 场会议，合计 ${usage.data.totalMinutes} 分钟`
    : ''

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-lg font-medium">统计看板</h2>
        <div className="flex items-center gap-2">
          {/* 汇总行是**接口数字的直出**：图表（canvas）不方便断言，
              这行文字让"图表数据与接口一致"这条验收可以被脚本核对 */}
          <Text type="secondary" data-testid="usage-summary">
            {summary}
          </Text>
          <RangePicker
            value={range}
            allowClear={false}
            onChange={(v) => v && v[0] && v[1] && setRange([v[0], v[1]])}
          />
        </div>
      </div>

      <Card title={`会议室使用率（${usage.data?.start ?? ''} ~ ${usage.data?.end ?? ''}）`}>
        <Spin spinning={usage.loading}>
          {usage.data && usage.data.items.length > 0 ? (
            <Chart option={usageOption} />
          ) : (
            <Empty description="暂无数据" />
          )}
        </Spin>
      </Card>

      <Card title={`我的会议（近 ${mine.data?.days ?? 30} 天）`}>
        <Spin spinning={mine.loading}>
          {mine.data && mine.data.daily.length > 0 ? (
            <Chart option={mineOption} />
          ) : (
            <Empty description="近 30 天还没有会议" />
          )}
        </Spin>
      </Card>
    </div>
  )
}
