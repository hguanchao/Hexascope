/*
 * 文件说明：前端知识库检索追踪页签，按审查记录查看每次评分的检索过程。
 */
import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Col, Input, Row, Space, Table, Tag, Typography, message } from 'antd'
import { SearchOutlined } from '@ant-design/icons'
import type { ColumnsType, TableProps } from 'antd/es/table'
import dayjs from 'dayjs'
import { cleanKnowledgeTraces, getKnowledgeTraces } from '../../services/knowledgeBase'
import type { KnowledgeTrace } from '../../types'

const { Text, Paragraph } = Typography

export default function TraceTab() {
  const [loading, setLoading] = useState(false)
  const [traces, setTraces] = useState<KnowledgeTrace[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)
  const [reviewIdInput, setReviewIdInput] = useState('')
  const [reviewId, setReviewId] = useState<string | undefined>(undefined)

  const fetchTraces = useCallback(async () => {
    setLoading(true)
    try {
      const data = await getKnowledgeTraces({ reviewId, page, pageSize })
      setTraces(data.items)
      setTotal(data.total)
    } finally {
      setLoading(false)
    }
  }, [reviewId, page, pageSize])

  useEffect(() => {
    fetchTraces()
  }, [fetchTraces])

  const handleSearch = () => {
    setPage(1)
    setReviewId(reviewIdInput.trim() || undefined)
  }

  const handleClean = async () => {
    const data = await cleanKnowledgeTraces(30)
    message.success(`已清理 30 天前的检索追踪 ${data.deleted} 条`)
    fetchTraces()
  }

  const handleTableChange: TableProps<KnowledgeTrace>['onChange'] = (pagination) => {
    setPage(pagination.current ?? 1)
    setPageSize(pagination.pageSize ?? pageSize)
  }

  const columns: ColumnsType<KnowledgeTrace> = [
    {
      title: '时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 160,
      render: (value: string) => (value ? dayjs(value).format('MM-DD HH:mm:ss') : '-'),
    },
    {
      title: '审查记录 ID',
      dataIndex: 'reviewId',
      key: 'reviewId',
      width: 200,
      render: (value?: string) => (value ? <Text code>{value}</Text> : '-'),
    },
    {
      title: 'Query 数',
      dataIndex: 'queries',
      key: 'queries',
      width: 90,
      render: (queries: string[]) => queries.length,
    },
    {
      title: '候选数',
      dataIndex: 'candidates',
      key: 'candidates',
      width: 90,
      render: (candidates: Array<{ id?: string }>) => candidates.length,
    },
    {
      title: '精排命中',
      dataIndex: 'selected',
      key: 'selected',
      width: 90,
      render: (selected: string[]) => selected.length,
    },
    {
      title: '检索耗时',
      dataIndex: 'retrievalMs',
      key: 'retrievalMs',
      width: 100,
      render: (value?: number) => (value != null ? `${value}ms` : '-'),
    },
    {
      title: '精排耗时',
      dataIndex: 'rerankMs',
      key: 'rerankMs',
      width: 100,
      render: (value?: number) => (value != null ? `${value}ms` : '-'),
    },
    {
      title: '总耗时',
      dataIndex: 'totalMs',
      key: 'totalMs',
      width: 100,
      render: (value?: number) => (value != null ? `${value}ms` : '-'),
    },
    {
      title: '混合检索',
      dataIndex: 'hybridEnabled',
      key: 'hybridEnabled',
      width: 100,
      render: (enabled: boolean) =>
        enabled ? <Tag color="blue">混合</Tag> : <Tag color="default">纯向量</Tag>,
    },
  ]

  return (
    <Card
      title="检索追踪"
      extra={
        <Space>
          <Button size="small" onClick={handleClean}>
            清理 30 天前
          </Button>
        </Space>
      }
    >
      <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
        <Col xs={24} md={10}>
          <Input
            allowClear
            placeholder="输入审查记录 ID 定位检索记录（留空查全部）"
            value={reviewIdInput}
            onChange={(event) => setReviewIdInput(event.target.value)}
            onPressEnter={handleSearch}
          />
        </Col>
        <Col xs={24} md={8}>
          <Button type="primary" icon={<SearchOutlined />} onClick={handleSearch}>
            查询
          </Button>
        </Col>
      </Row>

      <Table
        rowKey="id"
        columns={columns}
        dataSource={traces}
        loading={loading}
        scroll={{ x: 1100 }}
        expandable={{
          expandedRowRender: (record) => (
            <div style={{ padding: '0 8px' }}>
              <Paragraph style={{ marginBottom: 4 }}>
                <Text strong>Query 列表</Text>
              </Paragraph>
              {record.queries.map((query, index) => (
                <Paragraph key={index} ellipsis={{ rows: 1, tooltip: query }} style={{ marginBottom: 4 }}>
                  <Text type="secondary">Q{index + 1}:</Text> {query}
                </Paragraph>
              ))}
              <Paragraph style={{ marginBottom: 4 }}>
                <Text strong>候选 ID</Text>（{record.candidates.length} 条）
              </Paragraph>
              <Paragraph ellipsis={{ rows: 2, tooltip: record.candidates.map((c) => c.id).join(', ') }} style={{ marginBottom: 4 }}>
                <Text type="secondary">{record.candidates.map((c) => c.id ?? '-').join(', ')}</Text>
              </Paragraph>
              <Paragraph style={{ marginBottom: 0 }}>
                <Text strong>精排保留</Text>（{record.selected.length} 条）
              </Paragraph>
              <Paragraph ellipsis={{ rows: 2, tooltip: record.selected.join(', ') }} style={{ marginBottom: 0 }}>
                <Text type="secondary">{record.selected.join(', ') || '-'}</Text>
              </Paragraph>
            </div>
          ),
        }}
        pagination={{
          current: page,
          pageSize,
          total,
          showSizeChanger: true,
          showTotal: (count) => `共 ${count} 条`,
        }}
        onChange={handleTableChange}
      />
    </Card>
  )
}