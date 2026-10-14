/**
 * src/pages/admin/RoomManage.tsx
 * 会议室管理页（ADMIN）：列表 + 分组/状态筛选 + 新建/编辑弹窗 + 删除 + 策略配置入口。
 * 数据统一走 useAppRequest；列表筛选变化时手动 run 重查；策略抽屉保存后刷新（RoomVO 含策略字段）。
 * M1 里程碑演示：admin 在此完成"新增会议室 → 配策略 → 列表可见"闭环。
 */
import { useEffect, useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import {
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tag,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { Plus } from 'lucide-react'
import {
  createRoom,
  deleteRoom,
  listRoomGroups,
  listRooms,
  updateRoom,
  type Room,
  type RoomQuery,
  type RoomSaveInput,
} from '@/api/room'
import { useAppRequest } from '@/hooks/useAppRequest'
import StrategyDrawer from './StrategyDrawer'

// 新建/编辑表单校验（范围对齐后端 RoomSaveDTO）
const roomSchema = z.object({
  name: z.string().trim().min(1, '请输入名称').max(50, '最长 50 字符'),
  groupId: z.number({ invalid_type_error: '请选择分组' }).int('请选择分组').positive('请选择分组'),
  capacity: z
    .number({ invalid_type_error: '请输入容纳人数' })
    .int('请输入整数')
    .min(1, '至少 1 人')
    .max(200, '最多 200 人'),
  location: z.string().trim().max(100, '最长 100 字符'),
  deviceTags: z.array(z.string()).optional(),
  status: z.number().int().min(0).max(1),
})

type RoomForm = z.infer<typeof roomSchema>

export default function RoomManage() {
  // 列表筛选（groupId 精确 / status 精确）
  const [filter, setFilter] = useState<RoomQuery>({})
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<Room | null>(null)
  const [strategyRoom, setStrategyRoom] = useState<Room | null>(null)
  /** 正在删除的行 id：让 loading 精确到行，而不是全场按钮转圈 */
  const [deletingId, setDeletingId] = useState<number | null>(null)

  // 分组列表：挂载即加载一次
  const { data: groups } = useAppRequest(listRoomGroups, { manual: false })

  // 会议室列表：筛选变化时重查
  const { data: rooms, loading, run } = useAppRequest(listRooms)
  useEffect(() => {
    run(filter)
  }, [filter.groupId, filter.status, run])

  const { runAsync: doDelete } = useAppRequest(deleteRoom)
  const { runAsync: doCreate, loading: creating } = useAppRequest(createRoom)
  const { runAsync: doUpdate, loading: updating } = useAppRequest(updateRoom)

  const {
    control,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<RoomForm>({
    resolver: zodResolver(roomSchema),
    defaultValues: { name: '', location: '', deviceTags: [], status: 0 },
  })

  const refresh = () => run(filter)

  const openCreate = () => {
    setEditing(null)
    reset({ name: '', location: '', deviceTags: [], status: 0, groupId: undefined, capacity: undefined })
    setModalOpen(true)
  }

  const openEdit = (room: Room) => {
    setEditing(room)
    reset({
      name: room.name,
      groupId: room.groupId,
      capacity: room.capacity,
      location: room.location ?? '',
      deviceTags: room.deviceTags ?? [],
      status: room.status,
    })
    setModalOpen(true)
  }

  const onSubmit = async (values: RoomForm) => {
    try {
      const input: RoomSaveInput = {
        name: values.name,
        groupId: values.groupId,
        capacity: values.capacity,
        location: values.location || undefined,
        deviceTags: values.deviceTags ?? [],
        status: values.status,
      }
      if (editing) {
        await doUpdate(editing.id, input)
      } else {
        await doCreate(input)
      }
      setModalOpen(false)
      refresh()
    } catch {
      // 失败已由 useAppRequest 统一 toast；此处收敛 reject，避免 Uncaught (in promise)
    }
  }

  const handleDelete = async (id: number) => {
    setDeletingId(id)
    try {
      await doDelete(id)
      refresh()
    } catch {
      // 已 toast（如 30006：该会议室存在未结束的会议）
    } finally {
      setDeletingId(null)
    }
  }

  const groupOptions = (groups ?? []).map((g) => ({ label: g.name, value: g.id }))

  const columns: ColumnsType<Room> = [
    { title: '名称', dataIndex: 'name' },
    { title: '分组', dataIndex: 'groupName', width: 120 },
    { title: '容纳人数', dataIndex: 'capacity', width: 100 },
    { title: '位置', dataIndex: 'location', ellipsis: true },
    {
      title: '设备',
      dataIndex: 'deviceTags',
      width: 200,
      render: (tags: string[]) => (
        <>
          {(tags ?? []).map((t) => (
            <Tag key={t} className="mr-1">
              {t}
            </Tag>
          ))}
        </>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (status: number) =>
        status === 1 ? <Tag color="orange">维护中</Tag> : <Tag color="green">可用</Tag>,
    },
    {
      title: '操作',
      key: 'action',
      width: 180,
      render: (_, room) => (
        <Space size="small">
          <Button type="link" size="small" onClick={() => openEdit(room)}>
            编辑
          </Button>
          <Button type="link" size="small" onClick={() => setStrategyRoom(room)}>
            策略
          </Button>
          <Popconfirm
            title="确认删除该会议室？"
            onConfirm={() => handleDelete(room.id)}
            okText="删除"
            cancelText="取消"
          >
            <Button type="link" size="small" danger loading={deletingId === room.id}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <div className="mb-4 flex items-center justify-between">
        <Space>
          <Select
            allowClear
            placeholder="全部分组"
            style={{ width: 160 }}
            options={groupOptions}
            value={filter.groupId}
            onChange={(v) => setFilter((f) => ({ ...f, groupId: v }))}
          />
          <Select
            allowClear
            placeholder="全部状态"
            style={{ width: 120 }}
            options={[
              { label: '可用', value: 0 },
              { label: '维护中', value: 1 },
            ]}
            value={filter.status}
            onChange={(v) => setFilter((f) => ({ ...f, status: v }))}
          />
        </Space>
        <Button type="primary" icon={<Plus size={16} />} onClick={openCreate}>
          新增会议室
        </Button>
      </div>

      <Table<Room>
        rowKey="id"
        columns={columns}
        dataSource={rooms ?? []}
        loading={loading}
        pagination={false}
      />

      {/* 新建/编辑弹窗 */}
      <Modal
        title={editing ? '编辑会议室' : '新增会议室'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={handleSubmit(onSubmit)}
        confirmLoading={creating || updating}
        okText="保存"
        cancelText="取消"
        destroyOnClose
      >
        <Form layout="vertical">
          <Form.Item
            label="名称"
            required
            validateStatus={errors.name ? 'error' : ''}
            help={errors.name?.message}
          >
            <Controller
              name="name"
              control={control}
              render={({ field }) => <Input {...field} placeholder="如：3F 演示厅" />}
            />
          </Form.Item>

          <Form.Item
            label="分组"
            required
            validateStatus={errors.groupId ? 'error' : ''}
            help={errors.groupId?.message}
          >
            <Controller
              name="groupId"
              control={control}
              render={({ field }) => (
                <Select
                  {...field}
                  placeholder="选择分组"
                  options={groupOptions}
                  className="w-full"
                />
              )}
            />
          </Form.Item>

          <Form.Item
            label="容纳人数"
            required
            validateStatus={errors.capacity ? 'error' : ''}
            help={errors.capacity?.message}
          >
            <Controller
              name="capacity"
              control={control}
              render={({ field }) => (
                <InputNumber {...field} min={1} max={200} className="w-full" />
              )}
            />
          </Form.Item>

          <Form.Item
            label="位置"
            validateStatus={errors.location ? 'error' : ''}
            help={errors.location?.message}
          >
            <Controller
              name="location"
              control={control}
              render={({ field }) => <Input {...field} placeholder="如：3F 东侧" />}
            />
          </Form.Item>

          <Form.Item label="设备标签">
            <Controller
              name="deviceTags"
              control={control}
              render={({ field }) => (
                <Select
                  {...field}
                  mode="tags"
                  placeholder="输入后回车添加（如：投影仪、白板）"
                  open={false}
                />
              )}
            />
          </Form.Item>

          <Form.Item label="维护状态">
            <Controller
              name="status"
              control={control}
              render={({ field }) => (
                <Switch
                  checked={field.value === 1}
                  onChange={(v) => field.onChange(v ? 1 : 0)}
                  checkedChildren="维护中"
                  unCheckedChildren="可用"
                />
              )}
            />
          </Form.Item>
        </Form>
      </Modal>

      {/* 策略配置抽屉 */}
      <StrategyDrawer
        open={!!strategyRoom}
        room={strategyRoom}
        onClose={() => setStrategyRoom(null)}
        onSaved={refresh}
      />
    </div>
  )
}
