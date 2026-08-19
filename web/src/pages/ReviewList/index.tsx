/*
 * 文件说明：前端页面组件，承载具体业务页面的数据加载、交互和展示逻辑。
 */
import { useEffect, useState } from 'react'
import {
  Table,
  Card,
  Input,
  Select,
  DatePicker,
  Space,
  Button,
  Tag,
  Form,
  Row,
  Col,
  Modal,
  message,
} from 'antd'
import { PlusOutlined, ReloadOutlined, SearchOutlined, ClearOutlined } from '@ant-design/icons'
import type { ColumnsType, TableProps } from 'antd/es/table'
import dayjs from 'dayjs'
import { useNavigate } from 'react-router-dom'
import { useReviewStore } from '../../store/useReviewStore'
import type { ReviewListItem, ReviewStatus } from '../../types'
import {
  LEVEL_COLORS,
  LEVEL_LABELS,
  STATUS_COLORS,
  STATUS_LABELS,
  normalizeReviewLevel,
} from '../../utils/constants'
import ScoreBadge from '../../components/ScoreBadge'
import { createRequirement } from '../../services/review'
import type { CreateRequirementRequest } from '../../types'

const { RangePicker } = DatePicker

const STATUS_OPTIONS = [
  { label: '全部', value: '' },
  { label: '评分中', value: 'reviewing' },
  { label: '待处理', value: 'pending' },
  { label: '已通过', value: 'approved' },
  { label: '已拒绝', value: 'rejected' },
  { label: '需修改', value: 'needs_revision' },
  { label: '评分失败', value: 'review_failed' },
]

export default function ReviewList() {
  const navigate = useNavigate()
  const { list, total, loading, query, fetchList, setQuery, resetQuery, setPage } =
    useReviewStore()
  const [form] = Form.useForm()
  const [createForm] = Form.useForm<CreateRequirementRequest>()
  const [createOpen, setCreateOpen] = useState(false)
  const [creating, setCreating] = useState(false)

  useEffect(() => {
    fetchList()
  }, [
    query.page,
    query.pageSize,
    query.sortBy,
    query.sortOrder,
    query.status,
    query.teamId,
    query.minScore,
    query.maxScore,
    query.creator,
    query.startTime,
    query.endTime,
  ])

  useEffect(() => {
    // 列表中存在“评分中”记录时短轮询刷新，让用户能看到评分完成或失败状态。
    if (!list.some((item) => item.status === 'reviewing')) return
    const timer = window.setTimeout(() => {
      void fetchList()
    }, 3000)
    return () => window.clearTimeout(timer)
  }, [list, fetchList])

  const handleSearch = () => {
    setPage(1)
    fetchList()
  }

  const handleReset = () => {
    form.resetFields()
    resetQuery()
    fetchList()
  }

  const generateRequirementId = () => `REQ-${Date.now()}`

  const openCreateModal = () => {
    createForm.setFieldsValue({
      requirementId: generateRequirementId(),
      title: '',
      description: '',
      creator: '',
      priority: 'Medium',
      workspaceId: 'default-workspace',
      teamId: 'default-team',
    })
    setCreateOpen(true)
  }

  const handleCreate = async () => {
    const values = await createForm.validateFields()
    setCreating(true)
    try {
      await createRequirement(values)
      message.success('需求已创建，AI 正在评分')
      setCreateOpen(false)
      createForm.resetFields()
      setPage(1)
      await fetchList()
    } finally {
      setCreating(false)
    }
  }

  const handleTableChange: TableProps<ReviewListItem>['onChange'] = (pagination, _filters, sorter) => {
    const s = sorter as { field?: string; order?: 'ascend' | 'descend' }
    if (s.field && s.order) {
      setQuery({
        sortBy: s.field,
        sortOrder: s.order === 'ascend' ? 'asc' : 'desc',
        page: 1,
      })
    } else {
      setQuery({ sortBy: undefined, sortOrder: undefined, page: 1 })
    }
    setPage(pagination.current ?? 1, pagination.pageSize)
  }

  const columns: ColumnsType<ReviewListItem> = [
    {
      title: '需求名称',
      dataIndex: 'requirementTitle',
      key: 'requirementTitle',
      width: 240,
      ellipsis: true,
      render: (text: string, record: ReviewListItem) => (
        <a onClick={() => navigate(`/requirements/${record.id}`)}>{text || record.requirementId}</a>
      ),
    },
    {
      title: '团队',
      dataIndex: 'teamId',
      key: 'teamId',
      width: 120,
    },
    {
      title: '创建人',
      dataIndex: 'creator',
      key: 'creator',
      width: 100,
    },
    {
      title: '总分',
      dataIndex: 'totalScore',
      key: 'totalScore',
      width: 120,
      sorter: true,
      sortOrder: query.sortBy === 'totalScore' ? (query.sortOrder === 'asc' ? 'ascend' : 'descend') : undefined,
      render: (score: number | null | undefined, record: ReviewListItem) =>
        score === undefined || score === null ? (
          <Tag color={STATUS_COLORS[record.status]}>{STATUS_LABELS[record.status] || '-'}</Tag>
        ) : (
          <ScoreBadge score={score} level={record.level} />
        ),
    },
    {
      title: '等级',
      dataIndex: 'level',
      key: 'level',
      width: 90,
      render: (level: ReviewListItem['level'], record) => {
        if (record.totalScore === undefined || record.totalScore === null) {
          return <span style={{ color: 'rgba(0,0,0,0.45)' }}>-</span>
        }
        const displayLevel = normalizeReviewLevel(level, record.totalScore)
        return (
          <Tag color={LEVEL_COLORS[displayLevel]} style={{ border: 'none', color: '#fff' }}>
            {LEVEL_LABELS[displayLevel]}
          </Tag>
        )
      },
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (status: ReviewStatus) => (
        <Tag color={STATUS_COLORS[status]}>{STATUS_LABELS[status]}</Tag>
      ),
    },
    {
      title: '审查时间',
      dataIndex: 'completedAt',
      key: 'completedAt',
      width: 160,
      sorter: true,
      sortOrder:
        query.sortBy === 'completedAt' ? (query.sortOrder === 'asc' ? 'ascend' : 'descend') : undefined,
      render: (t: string | undefined, record) => {
        const time = t || record.createdAt
        return time ? dayjs(time).format('YYYY-MM-DD HH:mm') : '-'
      },
    },
  ]

  return (
    <div className="page-container">
      <div className="page-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
        <div>
          <h2>需求列表</h2>
          <div className="page-subtitle">查看所有需求审查记录，支持筛选与排序</div>
        </div>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreateModal}>
          创建需求
        </Button>
      </div>

      <Card style={{ marginBottom: 16 }}>
        <Form form={form} layout="inline">
          <Row gutter={[16, 16]} style={{ width: '100%' }}>
            <Col xs={24} sm={12} lg={6}>
              <Form.Item name="teamId" label="团队" style={{ marginBottom: 0, width: '100%' }}>
                <Input
                  allowClear
                  placeholder="输入团队 ID"
                  onPressEnter={(e) => {
                    setQuery({ teamId: (e.target as HTMLInputElement).value || undefined, page: 1 })
                    fetchList()
                  }}
                  onChange={(e) => {
                    if (!e.target.value) setQuery({ teamId: undefined, page: 1 })
                  }}
                />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12} lg={6}>
              <Form.Item name="status" label="状态" style={{ marginBottom: 0, width: '100%' }}>
                <Select
                  allowClear
                  placeholder="全部状态"
                  options={STATUS_OPTIONS}
                  onChange={(v) => setQuery({ status: (v || undefined) as ReviewStatus | undefined, page: 1 })}
                />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12} lg={6}>
              <Form.Item name="creator" label="创建人" style={{ marginBottom: 0, width: '100%' }}>
                <Input
                  allowClear
                  placeholder="输入创建人"
                  onPressEnter={(e) => {
                    setQuery({ creator: (e.target as HTMLInputElement).value || undefined, page: 1 })
                    fetchList()
                  }}
                />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12} lg={6}>
              <Form.Item name="scoreRange" label="分数" style={{ marginBottom: 0, width: '100%' }}>
                <Space.Compact style={{ width: '100%' }}>
                  <Input
                    style={{ width: '40%' }}
                    placeholder="最低"
                    type="number"
                    onChange={(e) => {
                      const v = e.target.value
                      setQuery({ minScore: v ? Number(v) : undefined })
                    }}
                  />
                  <Input
                    style={{ width: '40%' }}
                    placeholder="最高"
                    type="number"
                    onChange={(e) => {
                      const v = e.target.value
                      setQuery({ maxScore: v ? Number(v) : undefined })
                    }}
                  />
                </Space.Compact>
              </Form.Item>
            </Col>
            <Col xs={24}>
              <Form.Item name="timeRange" label="时间" style={{ marginBottom: 0 }}>
                <RangePicker
                  style={{ width: 280 }}
                  onChange={(dates) => {
                    if (dates && dates[0] && dates[1]) {
                      setQuery({
                        startTime: dates[0].toISOString(),
                        endTime: dates[1].toISOString(),
                        page: 1,
                      })
                    } else {
                      setQuery({ startTime: undefined, endTime: undefined, page: 1 })
                    }
                  }}
                />
              </Form.Item>
            </Col>
          </Row>
        </Form>
        <Row justify="end" style={{ marginTop: 16 }}>
          <Space>
            <Button type="primary" icon={<SearchOutlined />} onClick={handleSearch}>
              查询
            </Button>
            <Button icon={<ClearOutlined />} onClick={handleReset}>
              重置
            </Button>
            <Button icon={<ReloadOutlined />} onClick={() => fetchList()} />
          </Space>
        </Row>
      </Card>

      <Card styles={{ body: { padding: 0 } }}>
        <Table<ReviewListItem>
          rowKey="id"
          columns={columns}
          dataSource={list}
          loading={loading}
          onChange={handleTableChange}
          scroll={{ x: 1000 }}
          pagination={{
            current: query.page,
            pageSize: query.pageSize,
            total,
            showSizeChanger: true,
            showQuickJumper: true,
            showTotal: (t) => `共 ${t} 条`,
            onChange: (page, pageSize) => setPage(page, pageSize),
          }}
        />
      </Card>

      <Modal
        title="创建需求"
        open={createOpen}
        onOk={handleCreate}
        onCancel={() => setCreateOpen(false)}
        confirmLoading={creating}
        okText="提交并评分"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={createForm} layout="vertical">
          <Form.Item
            label="需求 ID"
            name="requirementId"
            rules={[{ required: true, message: '请输入需求 ID' }]}
          >
            <Input
              placeholder="例如 REQ-202607050001"
              addonAfter={
                <Button
                  type="link"
                  size="small"
                  onClick={() => createForm.setFieldValue('requirementId', generateRequirementId())}
                >
                  生成
                </Button>
              }
            />
          </Form.Item>
          <Form.Item
            label="需求标题"
            name="title"
            rules={[{ required: true, message: '请输入需求标题' }]}
          >
            <Input placeholder="请输入需求标题" />
          </Form.Item>
          <Form.Item
            label="需求描述"
            name="description"
            rules={[{ required: true, message: '请输入需求描述' }]}
          >
            <Input.TextArea rows={6} placeholder="请输入背景、用户故事、验收标准等内容" />
          </Form.Item>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                label="创建人"
                name="creator"
                rules={[{ required: true, message: '请输入创建人' }]}
              >
                <Input placeholder="例如 张三" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                label="优先级"
                name="priority"
                rules={[{ required: true, message: '请选择优先级' }]}
              >
                <Select
                  options={[
                    { label: '高', value: 'High' },
                    { label: '中', value: 'Medium' },
                    { label: '低', value: 'Low' },
                  ]}
                />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                label="空间 ID"
                name="workspaceId"
                rules={[{ required: true, message: '请输入空间 ID' }]}
              >
                <Input placeholder="例如 workspace-a" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                label="团队 ID"
                name="teamId"
                rules={[{ required: true, message: '请输入团队 ID' }]}
              >
                <Input placeholder="例如 team-a" />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>
    </div>
  )
}
