/*
 * 文件说明：前端知识库评估页签，管理评估用例并对比混合检索的召回收益。
 */
import { useCallback, useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Col,
  Form,
  Input,
  Modal,
  Popconfirm,
  Row,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import { DeleteOutlined, PlayCircleOutlined, PlusOutlined } from '@ant-design/icons'
import type { ColumnsType, TableProps } from 'antd/es/table'
import dayjs from 'dayjs'
import {
  createEvalCase,
  deleteEvalCase,
  getEvalCases,
  getEvalSummary,
  runAllEval,
} from '../../services/knowledgeBase'
import type { CreateEvalCaseRequest, EvalCase, EvalSummary } from '../../types'
import { DIMENSION_LABELS } from '../../utils/constants'

const { Paragraph } = Typography

const dimensionOptions = Object.values(DIMENSION_LABELS).map((label) => ({ label, value: label }))

// 以百分比渲染指标，缺失显示 '-'
function renderRate(value?: number) {
  return value != null ? `${(value * 100).toFixed(1)}%` : '-'
}

// 渲染混合检索相对纯向量的召回差值
function renderRecallDiff(summary: EvalSummary) {
  if (summary.recallVector == null || summary.recallHybrid == null) {
    return '-'
  }
  const diff = summary.recallHybrid - summary.recallVector
  if (Math.abs(diff) < 0.0001) {
    return <Tag>持平</Tag>
  }
  return diff > 0 ? <Tag color="green">+{(diff * 100).toFixed(1)}%</Tag> : <Tag color="red">-{(-diff * 100).toFixed(1)}%</Tag>
}

export default function EvalTab() {
  const [cases, setCases] = useState<EvalCase[]>([])
  const [caseTotal, setCaseTotal] = useState(0)
  const [casePage, setCasePage] = useState(1)
  const [casePageSize, setCasePageSize] = useState(10)
  const [summary, setSummary] = useState<EvalSummary[]>([])
  const [running, setRunning] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [runMode, setRunMode] = useState<'all' | 'vector' | 'hybrid'>('all')
  const [createForm] = Form.useForm<CreateEvalCaseRequest>()

  const fetchCases = useCallback(async () => {
    const data = await getEvalCases({ page: casePage, pageSize: casePageSize })
    setCases(data.items)
    setCaseTotal(data.total)
  }, [casePage, casePageSize])

  const fetchSummary = useCallback(async () => {
    const data = await getEvalSummary({ limit: 200 })
    setSummary(data)
  }, [])

  useEffect(() => {
    fetchCases()
  }, [fetchCases])

  useEffect(() => {
    fetchSummary()
  }, [fetchSummary])

  const handleRunAll = async () => {
    setRunning(true)
    try {
      const data = await runAllEval({ mode: runMode })
      message.success(`评估运行完成，共执行 ${data.executed} 次检索`)
      fetchSummary()
    } finally {
      setRunning(false)
    }
  }

  const handleCreate = async () => {
    const values = await createForm.validateFields()
    // 期望命中 ID 以文本域换行输入，提交前转成数组
    const rawIds: unknown = values.expectedDocIds
    const expectedDocIds =
      typeof rawIds === 'string' && rawIds.trim()
        ? rawIds
            .split('\n')
            .map((line) => line.trim())
            .filter(Boolean)
        : undefined
    await createEvalCase({ ...values, expectedDocIds })
    message.success('评估用例已创建')
    setModalOpen(false)
    createForm.resetFields()
    fetchCases()
  }

  const handleDelete = async (id: string) => {
    await deleteEvalCase(id)
    message.success('评估用例已删除')
    fetchCases()
  }

  const handleCaseTableChange: TableProps<EvalCase>['onChange'] = (pagination) => {
    setCasePage(pagination.current ?? 1)
    setCasePageSize(pagination.pageSize ?? casePageSize)
  }

  const caseColumns: ColumnsType<EvalCase> = [
    {
      title: 'Query',
      dataIndex: 'query',
      key: 'query',
      render: (query: string, record) => (
        <Paragraph ellipsis={{ rows: 2, tooltip: query }} style={{ marginBottom: 0 }}>
          {record.source ? <Tag color="purple">自动</Tag> : <Tag color="blue">人工</Tag>}
          {query}
        </Paragraph>
      ),
    },
    {
      title: '维度',
      dataIndex: 'dimension',
      key: 'dimension',
      width: 110,
      render: (dimension?: string) => dimension || '-',
    },
    {
      title: '期望命中',
      dataIndex: 'expectedCount',
      key: 'expectedCount',
      width: 100,
    },
    {
      title: '备注',
      dataIndex: 'note',
      key: 'note',
      width: 150,
      ellipsis: true,
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      key: 'updatedAt',
      width: 160,
      render: (value: string) => (value ? dayjs(value).format('MM-DD HH:mm') : '-'),
    },
    {
      title: '操作',
      key: 'action',
      width: 90,
      render: (_, record) => (
        <Popconfirm title="删除评估用例" description="关联的运行结果会一并删除。" okText="删除" cancelText="取消" onConfirm={() => handleDelete(record.id)}>
          <Button danger size="small" icon={<DeleteOutlined />}>
            删除
          </Button>
        </Popconfirm>
      ),
    },
  ]

  const summaryColumns: ColumnsType<EvalSummary> = [
    {
      title: 'Query',
      dataIndex: 'query',
      key: 'query',
      ellipsis: true,
    },
    {
      title: '维度',
      dataIndex: 'dimension',
      key: 'dimension',
      width: 110,
      render: (dimension?: string) => dimension || '-',
    },
    {
      title: '纯向量 recall',
      dataIndex: 'recallVector',
      key: 'recallVector',
      width: 120,
      render: renderRate,
    },
    {
      title: '纯向量 MRR',
      dataIndex: 'mrrVector',
      key: 'mrrVector',
      width: 110,
      render: (value?: number) => (value != null ? value.toFixed(3) : '-'),
    },
    {
      title: '混合 recall',
      dataIndex: 'recallHybrid',
      key: 'recallHybrid',
      width: 110,
      render: renderRate,
    },
    {
      title: '混合 MRR',
      dataIndex: 'mrrHybrid',
      key: 'mrrHybrid',
      width: 100,
      render: (value?: number) => (value != null ? value.toFixed(3) : '-'),
    },
    {
      title: '召回差',
      key: 'recallDiff',
      width: 110,
      render: (_, record) => renderRecallDiff(record),
    },
  ]

  return (
    <>
      <Card title="评估用例" style={{ marginBottom: 16 }}>
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="导入评分表时会自动按行生成评估用例（弱监督金标），也可人工补充"
          description="批量运行后在下方对比纯向量与混合检索的召回率（recall）与 MRR，验证关键词路带来的收益。"
        />
        <Table
          rowKey="id"
          size="small"
          columns={caseColumns}
          dataSource={cases}
          pagination={{
            current: casePage,
            pageSize: casePageSize,
            total: caseTotal,
            showSizeChanger: true,
            showTotal: (count) => `共 ${count} 个用例`,
          }}
          onChange={handleCaseTableChange}
        />
        <Space style={{ marginTop: 16 }}>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setModalOpen(true)}>
            新建用例
          </Button>
          <Select
            value={runMode}
            options={[
              { label: '两种都跑', value: 'all' },
              { label: '纯向量', value: 'vector' },
              { label: '混合检索', value: 'hybrid' },
            ]}
            style={{ width: 140 }}
            onChange={(mode) => setRunMode(mode)}
          />
          <Button icon={<PlayCircleOutlined />} type="primary" ghost loading={running} onClick={handleRunAll}>
            运行全部用例
          </Button>
        </Space>
      </Card>

      <Card title="指标对比（最近一次运行）">
        <Table
          rowKey="caseId"
          size="small"
          columns={summaryColumns}
          dataSource={summary}
          scroll={{ x: 900 }}
          pagination={false}
        />
      </Card>

      <Modal
        title="新建评估用例"
        open={modalOpen}
        okText="创建"
        cancelText="取消"
        onOk={handleCreate}
        onCancel={() => setModalOpen(false)}
      >
        <Form form={createForm} layout="vertical">
          <Form.Item
            name="query"
            label="检索 Query"
            rules={[{ required: true, message: '请输入检索 query' }]}
          >
            <Input.TextArea rows={3} placeholder="模拟一次知识库检索的输入" />
          </Form.Item>
          <Form.Item name="expectedDocIds" label="期望命中的片段 ID（每行一个，可选）">
            <Input.TextArea rows={3} placeholder="知识片段 ID 列表，一行一个" />
          </Form.Item>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="dimension" label="关联维度（可选）">
                <Select allowClear placeholder="选择维度" options={dimensionOptions} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="note" label="备注（可选）">
                <Input placeholder="备注" />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>
    </>
  )
}