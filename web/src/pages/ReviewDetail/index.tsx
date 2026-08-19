/*
 * 文件说明：前端页面组件，承载具体业务页面的数据加载、交互和展示逻辑。
 */
import { useEffect, useState } from 'react'
import {
  Card,
  Row,
  Col,
  Spin,
  Descriptions,
  Tag,
  Button,
  Space,
  Divider,
  List,
  Empty,
  Modal,
  Input,
  message,
  Tooltip,
} from 'antd'
import {
  ArrowLeftOutlined,
  CheckOutlined,
  CloseOutlined,
  EditOutlined,
  ReloadOutlined,
} from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import dayjs from 'dayjs'
import { useReviewStore } from '../../store/useReviewStore'
import {
  retriggerReview,
  updateReviewStatus,
} from '../../services/review'
import { getTeamStats } from '../../services/stats'
import type { DimensionKey, ReviewStatus, TeamStats } from '../../types'
import {
  DIMENSIONS,
  DIMENSION_LABELS,
  DIMENSION_SCORE_MAX,
  DIMENSION_WEIGHTS,
  LEVEL_COLORS,
  STATUS_COLORS,
  STATUS_LABELS,
  normalizeReviewLevel,
} from '../../utils/constants'
import RadarChart from '../../components/RadarChart'
import ScoreBadge from '../../components/ScoreBadge'

const { TextArea } = Input

export default function ReviewDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { detail, detailLoading, fetchDetail } = useReviewStore()
  const [teamStats, setTeamStats] = useState<TeamStats | null>(null)
  const [actionLoading, setActionLoading] = useState(false)
  const [retriggerModalOpen, setRetriggerModalOpen] = useState(false)
  const [retriggerReason, setRetriggerReason] = useState('')

  useEffect(() => {
    if (id) {
      fetchDetail(id)
    }
  }, [id])

  useEffect(() => {
    // 详情页处于“评分中”时短轮询当前记录，评分完成后自动展示报告。
    if (!id || detail?.status !== 'reviewing') return
    const timer = window.setTimeout(() => {
      void fetchDetail(id)
    }, 3000)
    return () => window.clearTimeout(timer)
  }, [id, detail?.status, fetchDetail])

  // 获取团队均值用于雷达图对比
  useEffect(() => {
    if (detail && typeof detail.totalScore === 'number') {
      getTeamStats({ teamId: detail.teamId })
        .then(setTeamStats)
        .catch(() => undefined)
    }
  }, [detail?.teamId, detail?.totalScore])

  const handleStatusChange = async (status: ReviewStatus) => {
    if (!id) return
    const statusLabels: Record<ReviewStatus, string> = {
      approved: '通过',
      rejected: '拒绝',
      needs_revision: '需修改',
      pending: '待处理',
      reviewing: '评分中',
      review_failed: '评分失败',
    }
    Modal.confirm({
      title: `确认${statusLabels[status]}该审查？`,
      content: '此操作将更新审查状态。',
      onOk: async () => {
        setActionLoading(true)
        try {
          await updateReviewStatus(id, { status })
          message.success('状态已更新')
          fetchDetail(id)
        } finally {
          setActionLoading(false)
        }
      },
    })
  }

  const handleRetrigger = async () => {
    if (!detail) return
    if (!retriggerReason.trim()) {
      message.warning('请填写重新审查原因')
      return
    }
    setActionLoading(true)
    try {
      await retriggerReview({
        requirementId: detail.requirementId,
        reason: retriggerReason,
      })
      message.success('已触发重新审查')
      setRetriggerModalOpen(false)
      setRetriggerReason('')
      if (id) fetchDetail(id)
    } finally {
      setActionLoading(false)
    }
  }

  if (detailLoading && !detail) {
    return (
      <div className="page-container" style={{ textAlign: 'center', paddingTop: 120 }}>
        <Spin size="large" tip="加载中..." />
      </div>
    )
  }

  if (!detail) {
    return (
      <div className="page-container">
        <Empty description="未找到审查记录">
          <Button type="primary" onClick={() => navigate('/requirements')}>
            返回列表
          </Button>
        </Empty>
      </div>
    )
  }

  // 构建雷达图数据；未出分时只作为组件兜底值，页面会改为展示评分中或失败占位。
  const radarScores = DIMENSIONS.reduce(
    (acc, key) => {
      acc[key] = detail.dimensions[key]?.score ?? 0
      return acc
    },
    {} as Record<DimensionKey, number>,
  )

  const avgScores = teamStats
    ? teamStats.dimensionAvg
    : undefined
  const totalScore = detail.totalScore
  const hasScore = typeof totalScore === 'number'
  const isScoring = detail.status === 'reviewing'
  const isReviewFailed = detail.status === 'review_failed'
  // 只有真正拿到评分后才允许人工通过、打回或要求修改，避免用户处理半成品结果。
  const displayLevel = hasScore ? normalizeReviewLevel(detail.level, totalScore) : 'fail'

  return (
    <div className="page-container">
      <div className="page-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
        <div>
          <Space align="center">
            <Button
              type="text"
              icon={<ArrowLeftOutlined />}
              onClick={() => navigate('/requirements')}
            />
            <h2 style={{ margin: 0 }}>{detail.requirementTitle}</h2>
            <Tag color={STATUS_COLORS[detail.status]}>{STATUS_LABELS[detail.status]}</Tag>
          </Space>
          <div className="page-subtitle" style={{ marginLeft: 40 }}>
            需求 ID: {detail.requirementId}
          </div>
        </div>
        <Space>
          <Tooltip title="重新触发 AI 审查">
            <Button
              icon={<ReloadOutlined />}
              disabled={isScoring}
              onClick={() => setRetriggerModalOpen(true)}
            >
              重新审查
            </Button>
          </Tooltip>
          <Button
            type="primary"
            icon={<CheckOutlined />}
            loading={actionLoading}
            disabled={!hasScore || isScoring}
            onClick={() => handleStatusChange('approved')}
            style={{ background: LEVEL_COLORS.excellent, borderColor: LEVEL_COLORS.excellent }}
          >
            通过
          </Button>
          <Button
            icon={<EditOutlined />}
            loading={actionLoading}
            disabled={!hasScore || isScoring}
            onClick={() => handleStatusChange('needs_revision')}
          >
            需修改
          </Button>
          <Button
            danger
            icon={<CloseOutlined />}
            loading={actionLoading}
            disabled={!hasScore || isScoring}
            onClick={() => handleStatusChange('rejected')}
          >
            拒绝
          </Button>
        </Space>
      </div>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={12}>
          <Card title="维度评分雷达图" styles={{ body: { padding: 16 } }}>
            {hasScore ? (
              <RadarChart scores={radarScores} avgScores={avgScores} height={380} />
            ) : (
              <Empty description={isReviewFailed ? '评分失败' : 'AI 正在评分'} style={{ padding: '96px 0' }} />
            )}
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title="评分总览" style={{ marginBottom: 16 }}>
            {hasScore ? (
              <div style={{ textAlign: 'center', padding: '16px 0' }}>
                <div style={{ fontSize: 14, color: 'rgba(0,0,0,0.45)' }}>综合评分</div>
                <div
                  style={{
                    fontSize: 48,
                    fontWeight: 700,
                    color: LEVEL_COLORS[displayLevel],
                    lineHeight: 1.2,
                  }}
                >
                  {totalScore.toFixed(1)}
                </div>
                <ScoreBadge score={totalScore} level={detail.level} />
              </div>
            ) : (
              <div style={{ textAlign: 'center', padding: '32px 0' }}>
                {isScoring ? <Spin /> : null}
                <div style={{ marginTop: 12, color: 'rgba(0,0,0,0.55)' }}>
                  {isReviewFailed ? '评分失败，可重新审查' : 'AI 正在评分'}
                </div>
              </div>
            )}
            <Divider />
            <Descriptions column={2} size="small">
              <Descriptions.Item label="团队">{detail.teamName || detail.teamId}</Descriptions.Item>
              <Descriptions.Item label="创建人">{detail.creator}</Descriptions.Item>
              <Descriptions.Item label="优先级">{detail.priority || '-'}</Descriptions.Item>
              <Descriptions.Item label="创建时间">
                {dayjs(detail.createdAt).format('YYYY-MM-DD HH:mm')}
              </Descriptions.Item>
              <Descriptions.Item label="审查时间">
                {detail.completedAt ? dayjs(detail.completedAt).format('YYYY-MM-DD HH:mm') : '-'}
              </Descriptions.Item>
            </Descriptions>
          </Card>

          <Card title="AI 审查总结" style={{ marginBottom: 16 }}>
            <p style={{ margin: 0, color: 'rgba(0,0,0,0.75)', lineHeight: 1.7 }}>
              {detail.summary || detail.improvementSuggestion || '暂无 AI 总结'}
            </p>
          </Card>
        </Col>
      </Row>

      <Card title="各维度评分明细" style={{ marginTop: 16 }}>
        {hasScore ? (
          <Row gutter={[16, 16]}>
            {DIMENSIONS.map((key) => {
              const dim = detail.dimensions[key]
              const score = dim?.score ?? 0
              const scorePercent = score * 10
              const color = LEVEL_COLORS[
                scorePercent >= 85 ? 'excellent' : scorePercent >= 70 ? 'good' : scorePercent >= 55 ? 'warning' : 'fail'
              ]
              return (
                <Col xs={24} sm={12} lg={8} key={key}>
                  <Card
                    size="small"
                    styles={{ body: { padding: 16 } }}
                    style={{ borderLeft: `4px solid ${color}` }}
                  >
                    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}>
                      <span style={{ fontWeight: 600 }}>{DIMENSION_LABELS[key]}</span>
                      <span style={{ fontSize: 12, color: 'rgba(0,0,0,0.45)' }}>
                        权重 {(DIMENSION_WEIGHTS[key] * 100).toFixed(0)}%
                      </span>
                    </div>
                    <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, marginBottom: 8 }}>
                      <span style={{ fontSize: 28, fontWeight: 700, color }}>{score.toFixed(1)}</span>
                      <span style={{ color: 'rgba(0,0,0,0.45)' }}>/ {DIMENSION_SCORE_MAX}</span>
                    </div>
                    {dim?.suggestions && dim.suggestions.length > 0 ? (
                      <List
                        size="small"
                        split={false}
                        dataSource={dim.suggestions}
                        renderItem={(item) => (
                          <List.Item style={{ padding: '4px 0', color: 'rgba(0,0,0,0.65)', fontSize: 13 }}>
                            - {item}
                          </List.Item>
                        )}
                      />
                    ) : (
                      <span style={{ color: 'rgba(0,0,0,0.45)', fontSize: 13 }}>暂无改进建议</span>
                    )}
                  </Card>
                </Col>
              )
            })}
          </Row>
        ) : (
          <Empty description={isReviewFailed ? '评分失败' : 'AI 正在评分'} />
        )}
      </Card>

      {detail.requirementDescription && (
        <Card title="需求描述" style={{ marginTop: 16 }}>
          <pre style={{ margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word', color: 'rgba(0,0,0,0.75)', lineHeight: 1.7 }}>
            {detail.requirementDescription}
          </pre>
        </Card>
      )}

      <Modal
        title="重新触发 AI 审查"
        open={retriggerModalOpen}
        onOk={handleRetrigger}
        onCancel={() => {
          setRetriggerModalOpen(false)
          setRetriggerReason('')
        }}
        confirmLoading={actionLoading}
        okText="确认触发"
        cancelText="取消"
      >
        <p style={{ color: 'rgba(0,0,0,0.65)' }}>请填写重新审查的原因：</p>
        <TextArea
          rows={4}
          value={retriggerReason}
          onChange={(e) => setRetriggerReason(e.target.value)}
          placeholder="例如：需求内容已更新，需要重新评分"
          maxLength={200}
          showCount
        />
      </Modal>
    </div>
  )
}
