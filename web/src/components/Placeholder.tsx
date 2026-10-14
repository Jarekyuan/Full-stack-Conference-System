/**
 * src/components/Placeholder.tsx
 * 占位页：T1.F3 只搭路由骨架，各业务页面（日历/会议/审批等）在后续任务实现，
 * 先统一用这个占位组件顶住路由，保证菜单可点、路由可达。
 */
import { Result } from 'antd'

interface PlaceholderProps {
  title: string
}

export default function Placeholder({ title }: PlaceholderProps) {
  return (
    <div className="flex h-full items-center justify-center">
      <Result
        status="info"
        title={title}
        subTitle="该页面在后续任务中实现（当前为路由骨架占位）"
      />
    </div>
  )
}
