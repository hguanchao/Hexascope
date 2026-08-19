/*
 * 文件说明：前端知识库版本历史页签，展示知识源导入链与版本状态。
 */
import { useCallback, useEffect, useState } from 'react'
import { Card, Table, Tag } from 'antd'
import type { ColumnsType, TableProps } from 'antd/es/table'
import dayjs from 'dayjs'
import { getKnowledgeSources } from '../../services/knowledgeBase'
import type { KnowledgeSource } from '../../types'

export default function VersionHistory() {
  const [loading, setLoading] = useState(false)
  const [sources, setSources] = useState<KnowledgeSource[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)

  const fetchSources = useCallback(async () => {
    setLoading(true)
    try {
      const data = await getKnowledgeSources({ page, pageSize })
      setSources(data.items)
      setTotal(data.total)
    } finally {
      setLoading(false)
    }
  }, [page, pageSize])

  useEffect(() => {
    fetchSources()
  }, [fetchSources])

  const handleTableChange: TableProps<KnowledgeSource>['onChange'] = (pagination) => {
    setPage(pagination.current ?? 1)
    setPageSize(pagination.pageSize ?? pageSize)
  }

  const columns: ColumnsType<KnowledgeSource> = [
    {
      title: '文件名',
      dataIndex: 'fileName',
      key: 'fileName',
      ellipsis: true,
    },
    {
      title: '版本',
      dataIndex: 'version',
      key: 'version',
      width: 90,
      render: (version: number) => `V${version}`,
    },
    {
      title: '状态',
      dataIndex: 'active',
      key: 'active',
      width: 100,
      render: (active: boolean) =>
        active ? <Tag color="green">生效</Tag> : <Tag color="default">未生效</Tag>,
    },
    {
      title: '导入文档数',
      dataIndex: 'importedCount',
      key: 'importedCount',
      width: 110,
    },
    {
      title: '当前文档数',
      dataIndex: 'documentCount',
      key: 'documentCount',
      width: 110,
    },
    {
      title: '首次导入',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 170,
      render: (value: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-'),
    },
    {
      title: '最近更新',
      dataIndex: 'updatedAt',
      key: 'updatedAt',
      width: 170,
      render: (value: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-'),
    },
  ]

  return (
    <Card title="版本历史">
      <Table
        rowKey="id"
        columns={columns}
        dataSource={sources}
        loading={loading}
        scroll={{ x: 900 }}
        pagination={{
          current: page,
          pageSize,
          total,
          showSizeChanger: true,
          showTotal: (count) => `共 ${count} 个知识源`,
        }}
        onChange={handleTableChange}
      />
    </Card>
  )
}