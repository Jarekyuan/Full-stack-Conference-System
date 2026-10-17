/**
 * src/pages/Login.tsx
 * 登录页：React Hook Form + zod 校验 + antd 表单，登录成功写 userStore 并跳转。
 * 登录是"提交型"操作，走 useAppRequest 的 runAsync（manual），错误由统一 onError 兜底 toast。
 * 支持 ?redirect= 参数：路由守卫跳转过来时，登录后回到原目标页。
 */
import { Controller, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { Button, Card, Form, Input, Typography } from 'antd'
import { Lock, User } from 'lucide-react'
import { login } from '@/api/auth'
import ThemeToggle from '@/components/ThemeToggle'
import { useAppRequest } from '@/hooks/useAppRequest'
import { useUserStore } from '@/stores/userStore'

const loginSchema = z.object({
  username: z.string().min(1, '请输入用户名'),
  password: z.string().min(1, '请输入密码'),
})

type LoginForm = z.infer<typeof loginSchema>

/** 解析 redirect 参数：仅接受站内绝对路径（防开放重定向），否则回落日历页 */
function resolveRedirect(raw: string | null): string {
  if (raw && raw.startsWith('/') && !raw.startsWith('//')) {
    return raw
  }
  return '/calendar'
}

export default function Login() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const loginStore = useUserStore((s) => s.login)

  const {
    control,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginForm>({
    resolver: zodResolver(loginSchema),
    defaultValues: { username: '', password: '' },
  })

  const { runAsync: doLogin, loading } = useAppRequest(login)

  const onSubmit = async (values: LoginForm) => {
    try {
      const token = await doLogin(values.username, values.password)
      loginStore(token)
      navigate(resolveRedirect(searchParams.get('redirect')), { replace: true })
    } catch {
      // 失败已由 useAppRequest 统一 toast；此处收敛 reject，避免 Uncaught (in promise)
    }
  }

  return (
    <div className="relative flex min-h-screen items-center justify-center bg-slate-50 dark:bg-slate-950">
      <div className="absolute right-4 top-4">
        <ThemeToggle />
      </div>
      <Card className="w-[380px] shadow-sm">
        <div className="mb-6 text-center">
          <Typography.Title level={4} className="!mb-1">
            会议室预约系统
          </Typography.Title>
          <Typography.Text type="secondary">登录以继续</Typography.Text>
        </div>

        <Form layout="vertical" onFinish={handleSubmit(onSubmit)}>
          <Form.Item
            label="用户名"
            validateStatus={errors.username ? 'error' : ''}
            help={errors.username?.message}
          >
            <Controller
              name="username"
              control={control}
              render={({ field }) => (
                <Input
                  {...field}
                  prefix={<User size={16} />}
                  placeholder="请输入用户名"
                  autoComplete="username"
                />
              )}
            />
          </Form.Item>

          <Form.Item
            label="密码"
            validateStatus={errors.password ? 'error' : ''}
            help={errors.password?.message}
          >
            <Controller
              name="password"
              control={control}
              render={({ field }) => (
                <Input.Password
                  {...field}
                  prefix={<Lock size={16} />}
                  placeholder="请输入密码"
                  autoComplete="current-password"
                />
              )}
            />
          </Form.Item>

          <Button type="primary" htmlType="submit" block loading={loading} className="mt-2">
            登录
          </Button>
        </Form>
      </Card>
    </div>
  )
}
