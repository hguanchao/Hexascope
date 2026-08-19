/*
 * 文件说明：前端页面组件，承载具体业务页面的数据加载、交互和展示逻辑。
 */
import { useEffect, useState } from 'react'
import { Card, Col, Row, Statistic, Table, Spin, Empty, Select } from 'antd'
import { ArrowUpOutlined, CheckCircleOutlined, ClockCircleOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import ReactECharts from 'echarts-for-react'
import type { EChartsOption } from 'echarts'
import type { StatsData, TeamRanking, TrendPoint } from '../../types'
import { getOverviewStats, getTrendStats } from '../../services/stats'
import {
  LEVEL_COLORS,
  LEVEL_LABELS,
  PERIOD_OPTIONS,
  SCORE_THRESHOLDS,
} from '../../utils/constants'
import TrendChart from '../../components/TrendChart'
import ScoreBadge from '../../components/ScoreBadge'

type Period = 'week' | 'month' | 'quarter'

export default function Dashboard() {
  const [stats, setStats] = useState<StatsData | null>(null)
  const [trend, setTrend] = useState<TrendPoint[]>([])
  const [period, setPeriod] = useState<Period>('week')
  const [loading, setLoading] = useState(true)
  const [trendLoading, setTrendLoading] = useState(true)

  const fetchStats = async () => {
    setLoading(true)
    try {
      const data = await getOverviewStats()
      setStats(data)
    } finally {
      setLoading(false)
    }
  }

  const fetchTrend = async () => {
    setTrendLoading(true)
    try {
      const data = await getTrendStats({ period })
      setTrend(data)
    } finally {
      setTrendLoading(false)
    }
  }

  useEffect(() => {
    fetchStats()
  }, [])

  useEffect(() => {
    fetchTrend()
  }, [period])

  const rankColumns: ColumnsType<TeamRanking> = [
    {
      title: '排名',
      dataIndex: 'rank',
      key: 'rank',
      width: 60,
      render: (_, __, index) => (
        <span
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            width: 24,
            height: 24,
            borderRadius: '50%',
            background: index < 3 ? LEVEL_COLORS.excellent : 'rgba(0,0,0,0.06)',
            color: index < 3 ? '#fff' : 'rgba(0,0,0,0.65)',
            fontSize: 12,
            fontWeight: 600,
          }}
        >
          {index + 1}
        </span>
      ),
    },
    { title: '团队', dataIndex: 'teamName', key: 'teamName' },
    {
      title: '平均分',
      dataIndex: 'avgScore',
      key: 'avgScore',
      width: 90,
      render: (score: number) => <ScoreBadge score={score} />,
      sorter: (a, b) => a.avgScore - b.avgScore,
    },
    {
      title: '合格率',
      dataIndex: 'passRate',
      key: 'passRate',
      width: 100,
      render: (rate: number) => `${(rate * 100).toFixed(1)}%`,
    },
    {
      title: '审查数',
      dataIndex: 'reviewCount',
      key: 'reviewCount',
      width: 80,
    },
  ]

  // 评分分布饼图
  const distributionOption: EChartsOption = {
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { bottom: 0 },
    series: [
      {
        type: 'pie',
        radius: ['40%', '70%'],
        avoidLabelOverlap: false,
        itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
        label: { show: false },
        emphasis: { label: { show: true, fontSize: 14, fontWeight: 'bold' } },
        data: stats
          ? [
              { value: stats.scoreDistribution.excellent, name: LEVEL_LABELS.excellent, itemStyle: { color: LEVEL_COLORS.excellent } },
              { value: stats.scoreDistribution.good, name: LEVEL_LABELS.good, itemStyle: { color: LEVEL_COLORS.good } },
              { value: stats.scoreDistribution.warning, name: LEVEL_LABELS.warning, itemStyle: { color: LEVEL_COLORS.warning } },
              { value: stats.scoreDistribution.fail, name: LEVEL_LABELS.fail, itemStyle: { color: LEVEL_COLORS.fail } },
            ]
          : [],
      },
    ],
  }

  return (
    <div className="page-container">
      <div className="page-header">
        <h2>概览</h2>
        <div className="page-subtitle">需求审查整体数据统计与趋势</div>
      </div>

      <Spin spinning={loading}>
        <Row gutter={[16, 16]}>
          <Col xs={24} sm={12} lg={6}>
            <Card>
              <Statistic
                title="平均评分"
                value={stats?.avgScore ?? 0}
                precision={1}
                valueStyle={{ color: LEVEL_COLORS.good }}
                prefix={<ArrowUpOutlined />}
                suffix="/ 100"
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Card>
              <Statistic
                title="合格率"
                value={stats ? (stats.passRate * 100).toFixed(1) : 0}
                precision={1}
                valueStyle={{ color: LEVEL_COLORS.excellent }}
                suffix="%"
                prefix={<CheckCircleOutlined />}
              />
              <div style={{ fontSize: 12, color: 'rgba(0,0,0,0.45)', marginTop: 4 }}>
                合格线 {SCORE_THRESHOLDS.GOOD} 分
              </div>
            </Card>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Card>
              <Statistic
                title="待审查数"
                value={stats?.pendingCount ?? 0}
                valueStyle={{ color: LEVEL_COLORS.warning }}
                prefix={<ClockCircleOutlined />}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Card>
              <Statistic
                title="累计审查数"
                value={stats?.totalReviewed ?? 0}
                valueStyle={{ color: 'rgba(0,0,0,0.85)' }}
              />
            </Card>
          </Col>
        </Row>
      </Spin>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} lg={10}>
          <Card title="评分分布" styles={{ body: { minHeight: 320 } }}>
            {stats && stats.totalReviewed > 0 ? (
              <ReactECharts
                option={distributionOption}
                style={{ height: 300, width: '100%' }}
                opts={{ renderer: 'svg' }}
              />
            ) : (
              <Empty description="暂无数据" style={{ paddingTop: 80 }} />
            )}
          </Card>
        </Col>
        <Col xs={24} lg={14}>
          <Card
            title="评分趋势"
            extra={
              <Select
                value={period}
                onChange={(v) => setPeriod(v as Period)}
                options={PERIOD_OPTIONS}
                style={{ width: 100 }}
              />
            }
            styles={{ body: { minHeight: 320 } }}
          >
            <Spin spinning={trendLoading}>
              {trend.length > 0 ? (
                <TrendChart data={trend} height={300} />
              ) : (
                <Empty description="暂无数据" style={{ paddingTop: 80 }} />
              )}
            </Spin>
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24}>
          <Card title="团队排名" styles={{ body: { padding: 0 } }}>
            <Table
              rowKey="teamId"
              columns={rankColumns}
              dataSource={stats?.teamRanking ?? []}
              pagination={false}
              loading={loading}
              size="middle"
              locale={{ emptyText: <Empty description="暂无数据" /> }}
            />
          </Card>
        </Col>
      </Row>
    </div>
  )
}
