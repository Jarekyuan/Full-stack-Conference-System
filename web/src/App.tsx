/**
 * 脚手架默认页（T1.F1 占位）
 * 核心功能：确认 Vite + React18 + TS + antd5 + Tailwind 四件套已正确装配，
 * 并标注当前里程碑进度，后续任务（T1.F2 请求层 / T1.F3 登录页）将替换本页内容。
 * 说明：本页只做静态展示，请求能力等 T1.F2 的 request.ts 落地后再接入。
 */
import { Tag, Typography } from 'antd'

const { Title, Paragraph, Text } = Typography

/** 技术栈基线：与 02 文档 3.3 节一一对应 */
const STACKS = [
  { label: 'React 18', tone: 'blue' },
  { label: 'TypeScript strict', tone: 'blue' },
  { label: 'Vite', tone: 'green' },
  { label: 'antd 5', tone: 'cyan' },
  { label: 'Tailwind CSS', tone: 'cyan' },
  { label: 'Zustand + immer', tone: 'purple' },
  { label: 'ahooks', tone: 'purple' },
  { label: 'axios', tone: 'orange' },
  { label: 'React Hook Form + zod', tone: 'orange' },
  { label: 'dayjs', tone: 'gold' },
  { label: 'ECharts', tone: 'gold' },
  { label: 'lucide-react', tone: 'default' },
] as const

export default function App() {
  return (
    <div className="min-h-full bg-slate-50 px-6 py-12">
      <div className="mx-auto max-w-3xl">
        <Title level={2} className="!mb-2">
          会议室预约系统
        </Title>
        <Paragraph type="secondary" className="!mb-8">
          前端脚手架已就绪（T1.F1）。布局与间距由 Tailwind 负责，组件外观由 antd 负责。
        </Paragraph>

        <div className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
          <Text strong className="mb-3 block">
            技术栈基线
          </Text>
          <div className="flex flex-wrap gap-2">
            {STACKS.map((item) => (
              <Tag key={item.label} color={item.tone} className="!m-0">
                {item.label}
              </Tag>
            ))}
          </div>
        </div>

        <Paragraph type="secondary" className="mt-8 !mb-0">
          下一步：T1.F2 请求层封装（axios 实例 + 401 single-flight 刷新）→ T1.F3 登录页与路由守卫。
        </Paragraph>
      </div>
    </div>
  )
}
