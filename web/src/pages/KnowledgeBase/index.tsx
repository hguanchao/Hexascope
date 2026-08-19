/*
 * 文件说明：前端知识库页面，负责导入、查询和维护 RAG 评分标准知识片段。
 */
import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import {
  Alert,
  Button,
  Card,
  Col,
  Input,
  Popconfirm,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tabs,
  Tag,
  Typography,
  Upload,
  message,
} from 'antd'
import {
  ClearOutlined,
  DeleteOutlined,
  InboxOutlined,
  ReloadOutlined,
  SaveOutlined,
  SearchOutlined,
} from '@ant-design/icons'
import type { ColumnsType, TableProps } from 'antd/es/table'
import type { UploadFile, UploadProps } from 'antd/es/upload/interface'
import {
  deleteKnowledgeDocument,
  getKnowledgeBaseStats,
  getKnowledgeDocuments,
  importKnowledgeBase,
} from '../../services/knowledgeBase'
import type {
  KnowledgeBaseStats,
  KnowledgeDocument,
  KnowledgeDocumentQuery,
  ScoringRubricImportResult,
} from '../../types'
import VersionHistory from './VersionHistory'
import TraceTab from './TraceTab'
import EvalTab from './EvalTab'

const { Paragraph } = Typography

interface RubricTemplateRow {
  key: string
  sheetName: string
  columns: string[]
}

const rubricTemplateRows: RubricTemplateRow[] = [
  {
    key: 'detail',
    sheetName: '评分细则',
    columns: ['维度', '等级', '分值范围', '标准描述', '好示例', '差示例'],
  },
  {
    key: 'penalty',
    sheetName: '扣分项参考',
    columns: ['维度', '扣分项', '扣分值', '严重程度', '说明'],
  },
]

const rubricTemplateColumns: ColumnsType<RubricTemplateRow> = [
  {
    title: 'Sheet',
    dataIndex: 'sheetName',
    width: 140,
    render: (sheetName: string) => <strong>{sheetName}</strong>,
  },
  {
    title: '列顺序',
    dataIndex: 'columns',
    render: (columns: string[]) => (
      <Space size={[0, 8]} wrap>
        {columns.map((column) => (
          <Tag key={column}>{column}</Tag>
        ))}
      </Space>
    ),
  },
]

// 渲染导入结果的行级差异摘要（新增 / 删除 / 变更）
function renderDiffSummary(result: ScoringRubricImportResult): ReactNode {
  const diff = result.diffSummary
  if (!diff) return null
  const summary: ReactNode[] = []
  if (diff.added?.length) {
    summary.push(<Tag key="added" color="green">新增 {diff.added.length}</Tag>)
  }
  if (diff.removed?.length) {
    summary.push(<Tag key="removed" color="red">删除 {diff.removed.length}</Tag>)
  }
  if (diff.changed?.length) {
    summary.push(<Tag key="changed" color="orange">变更 {diff.changed.length}</Tag>)
  }
  if (!summary.length) return null
  return (
    <>
      <br />
      <Space size={4} wrap style={{ marginTop: 8 }}>
        {summary}
      </Space>
    </>
  )
}

export default function KnowledgeBase() {
  const [fileList, setFileList] = useState<UploadFile[]>([])
  const [uploading, setUploading] = useState(false)
  const [loading, setLoading] = useState(false)
  const [stats, setStats] = useState<KnowledgeBaseStats | null>(null)
  const [documents, setDocuments] = useState<KnowledgeDocument[]>([])
  const [total, setTotal] = useState(0)
  const [keywordInput, setKeywordInput] = useState('')
  const [query, setQuery] = useState<KnowledgeDocumentQuery>({
    page: 1,
    pageSize: 10,
  })
  const [importResult, setImportResult] = useState<ScoringRubricImportResult | null>(null)

  const fetchStats = useCallback(async () => {
    const data = await getKnowledgeBaseStats()
    setStats(data)
  }, [])

  const fetchDocuments = useCallback(async () => {
    setLoading(true)
    try {
      const data = await getKnowledgeDocuments(query)
      setDocuments(data.items)
      setTotal(data.total)
    } finally {
      setLoading(false)
    }
  }, [query])

  useEffect(() => {
    fetchStats()
  }, [fetchStats])

  useEffect(() => {
    fetchDocuments()
  }, [fetchDocuments])

  const sourceOptions = useMemo(
    () => (stats?.sources ?? []).map((source) => ({ label: source, value: source })),
    [stats?.sources],
  )

  const uploadProps: UploadProps = {
    accept: '.xlsx,.xls',
    maxCount: 1,
    fileList,
    beforeUpload: () => false,
    onChange: ({ fileList: nextFileList }) => {
      setFileList(nextFileList.slice(-1))
      setImportResult(null)
    },
    onRemove: () => {
      setFileList([])
      setImportResult(null)
      return true
    },
  }

  const handleRefresh = useCallback(() => {
    fetchStats()
    fetchDocuments()
  }, [fetchDocuments, fetchStats])

  const handleImport = async () => {
    const file = fileList[0]?.originFileObj
    if (!file) {
      message.warning('请先选择评分表 Excel')
      return
    }

    setUploading(true)
    try {
      const result = await importKnowledgeBase(file)
      setImportResult(result)
      if (result.changed) {
        if (result.importedCount > 0) {
          message.success(`导入完成，已写入 ${result.importedCount} 条知识片段（V${result.version}）`)
        } else {
          message.warning('导入完成，但没有解析到评分标准')
        }
      } else {
        message.info('文件未变化，跳过重复导入')
      }
      setQuery((prev) => ({ ...prev, page: 1 }))
      handleRefresh()
    } finally {
      setUploading(false)
    }
  }

  const handleClearUpload = () => {
    setFileList([])
    setImportResult(null)
  }

  const handleSearch = () => {
    setQuery((prev) => ({
      ...prev,
      page: 1,
      keyword: keywordInput.trim() || undefined,
    }))
  }

  const handleResetSearch = () => {
    setKeywordInput('')
    setQuery((prev) => ({
      page: 1,
      pageSize: prev.pageSize,
    }))
  }

  const handleDelete = useCallback(
    async (id: string) => {
      await deleteKnowledgeDocument(id)
      message.success('知识片段已删除')
      handleRefresh()
    },
    [handleRefresh],
  )

  const handleTableChange: TableProps<KnowledgeDocument>['onChange'] = (pagination) => {
    setQuery((prev) => ({
      ...prev,
      page: pagination.current ?? 1,
      pageSize: pagination.pageSize ?? prev.pageSize,
    }))
  }

  const columns: ColumnsType<KnowledgeDocument> = useMemo(
    () => [
      {
        title: '来源',
        dataIndex: 'source',
        key: 'source',
        width: 120,
        render: (source: string) => <Tag color={source === '评分细则' ? 'blue' : 'orange'}>{source || '-'}</Tag>,
      },
      {
        title: '维度',
        dataIndex: 'dimension',
        key: 'dimension',
        width: 120,
        render: (dimension: string) => dimension || '-',
      },
      {
        title: '等级/严重程度',
        key: 'level',
        width: 130,
        render: (_, record) => record.level || record.severity || '-',
      },
      {
        title: '行号',
        dataIndex: 'row',
        key: 'row',
        width: 80,
        render: (row: number) => row || '-',
      },
      {
        title: '知识片段',
        dataIndex: 'content',
        key: 'content',
        render: (content: string) => (
          <Paragraph ellipsis={{ rows: 2, tooltip: content }} style={{ marginBottom: 0 }}>
            {content}
          </Paragraph>
        ),
      },
      {
        title: '操作',
        key: 'action',
        width: 90,
        fixed: 'right',
        render: (_, record) => (
          <Popconfirm
            title="删除知识片段"
            description="删除后将不再参与后续 AI 检索。"
            okText="删除"
            cancelText="取消"
            onConfirm={() => handleDelete(record.id)}
          >
            <Button danger size="small" icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        ),
      },
    ],
    [handleDelete],
  )

  return (
    <div className="page-container">
      <div className="page-header">
        <h2>知识库</h2>
        <div className="page-subtitle">导入并维护 AI 需求审查使用的 RAG 评分标准</div>
      </div>

      <Tabs
        items={[
          {
            key: 'documents',
            label: '知识片段',
            children: (
              <>
                <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
                  <Col xs={24} sm={8}>
                    <Card>
                      <Statistic title="知识片段总数" value={stats?.total ?? 0} />
                    </Card>
                  </Col>
        <Col xs={24} sm={8}>
          <Card>
            <Statistic title="评分细则" value={stats?.detailCount ?? 0} />
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card>
            <Statistic title="扣分项参考" value={stats?.penaltyCount ?? 0} />
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={24} xl={12}>
          <Card title="导入评分表">
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 16 }}
              message="导入评分表后会写入 RAG 向量知识库"
              description="后续提交需求时，AI 会自动检索这些评分标准作为审查依据。"
            />

            <Upload.Dragger {...uploadProps}>
              <p className="ant-upload-drag-icon">
                <InboxOutlined />
              </p>
              <p className="ant-upload-text">选择评分表 Excel</p>
              <p className="ant-upload-hint">支持 .xlsx / .xls 文件</p>
            </Upload.Dragger>

            <Space style={{ marginTop: 16 }}>
              <Button
                type="primary"
                icon={<SaveOutlined />}
                loading={uploading}
                disabled={fileList.length === 0}
                onClick={handleImport}
              >
                导入评分表
              </Button>
              <Button icon={<ClearOutlined />} disabled={fileList.length === 0 && !importResult} onClick={handleClearUpload}>
                清空
              </Button>
            </Space>

            {importResult && (
              <Alert
                type={importResult.changed && importResult.importedCount > 0 ? 'success' : 'warning'}
                showIcon
                style={{ marginTop: 16 }}
                message={`文件：${importResult.fileName}${importResult.version ? `（V${importResult.version}）` : ''}`}
                description={
                  <>
                    {importResult.changed
                      ? `已写入 ${importResult.importedCount} 条知识片段，跳过 ${importResult.skippedCount} 条`
                      : `文件未变化，跳过重复导入（当前库中 ${importResult.skippedCount} 条知识片段）`}
                    {renderDiffSummary(importResult)}
                  </>
                }
              />
            )}
          </Card>
        </Col>

        <Col xs={24} xl={12}>
          <Card title="评分表模板">
            <Table
              rowKey="key"
              size="small"
              columns={rubricTemplateColumns}
              dataSource={rubricTemplateRows}
              pagination={false}
            />
          </Card>
        </Col>
      </Row>

      <Card title="知识片段">
        <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
          <Col xs={24} md={10}>
            <Input
              allowClear
              placeholder="搜索知识片段内容"
              value={keywordInput}
              onChange={(event) => setKeywordInput(event.target.value)}
              onPressEnter={handleSearch}
            />
          </Col>
          <Col xs={24} md={6}>
            <Select
              allowClear
              placeholder="全部来源"
              value={query.source}
              options={sourceOptions}
              style={{ width: '100%' }}
              onChange={(source) => setQuery((prev) => ({ ...prev, page: 1, source }))}
            />
          </Col>
          <Col xs={24} md={8}>
            <Space wrap>
              <Button type="primary" icon={<SearchOutlined />} onClick={handleSearch}>
                查询
              </Button>
              <Button icon={<ClearOutlined />} onClick={handleResetSearch}>
                重置
              </Button>
              <Button icon={<ReloadOutlined />} onClick={handleRefresh}>
                刷新
              </Button>
            </Space>
          </Col>
        </Row>

        <Table
          rowKey="id"
          columns={columns}
          dataSource={documents}
          loading={loading}
          scroll={{ x: 1000 }}
          pagination={{
            current: query.page,
            pageSize: query.pageSize,
            total,
            showSizeChanger: true,
            showTotal: (count) => `共 ${count} 条`,
          }}
          onChange={handleTableChange}
        />
        </Card>
              </>
            ),
          },
          {
            key: 'history',
            label: '版本历史',
            children: <VersionHistory />,
          },
          {
            key: 'trace',
            label: '检索追踪',
            children: <TraceTab />,
          },
          {
            key: 'eval',
            label: '评估',
            children: <EvalTab />,
          },
        ]}
      />
    </div>
  )
}
