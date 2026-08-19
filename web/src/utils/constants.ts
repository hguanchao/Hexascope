/*
 * 文件说明：前端工具与常量模块，集中维护页面可复用配置。
 */
import type { DimensionKey, ReviewLevel } from '../types'

// API 基础路径
export const API_BASE_URL = '/api/v1'

// 评分等级阈值
export const SCORE_THRESHOLDS = {
  EXCELLENT: 85,
  GOOD: 70,
  WARNING: 55,
} as const

// 单项维度评分满分
export const DIMENSION_SCORE_MAX = 10

// 评分等级颜色映射
export const LEVEL_COLORS: Record<ReviewLevel, string> = {
  excellent: '#52c41a',
  good: '#1890ff',
  warning: '#faad14',
  fail: '#f5222d',
  EXCELLENT: '#52c41a',
  GOOD: '#1890ff',
  NEEDS_IMPROVEMENT: '#faad14',
  POOR: '#f5222d',
}

// 评分等级文本映射
export const LEVEL_LABELS: Record<ReviewLevel, string> = {
  excellent: '优秀',
  good: '良好',
  warning: '待改进',
  fail: '不合格',
  EXCELLENT: '优秀',
  GOOD: '良好',
  NEEDS_IMPROVEMENT: '待改进',
  POOR: '不合格',
}

// 审查状态颜色映射
export const STATUS_COLORS: Record<string, string> = {
  reviewing: 'processing',
  pending: 'default',
  approved: 'success',
  rejected: 'error',
  needs_revision: 'warning',
  review_failed: 'error',
}

// 审查状态文本映射
export const STATUS_LABELS: Record<string, string> = {
  reviewing: '评分中',
  pending: '待处理',
  approved: '已通过',
  rejected: '已拒绝',
  needs_revision: '需修改',
  review_failed: '评分失败',
}

// 六个评分维度
export const DIMENSIONS: DimensionKey[] = [
  'completeness',
  'clarity',
  'feasibility',
  'value_alignment',
  'testability',
  'format',
]

// 维度名称映射
export const DIMENSION_LABELS: Record<DimensionKey, string> = {
  completeness: '完整性',
  clarity: '明确性',
  feasibility: '可行性',
  value_alignment: '价值对齐',
  testability: '可测试性',
  format: '格式规范',
}

// 维度权重
export const DIMENSION_WEIGHTS: Record<DimensionKey, number> = {
  completeness: 0.25,
  clarity: 0.20,
  feasibility: 0.15,
  value_alignment: 0.20,
  testability: 0.10,
  format: 0.10,
}

// 根据分数获取等级
function getLevelByScore(score: number): ReviewLevel {
  if (score >= SCORE_THRESHOLDS.EXCELLENT) return 'excellent'
  if (score >= SCORE_THRESHOLDS.GOOD) return 'good'
  if (score >= SCORE_THRESHOLDS.WARNING) return 'warning'
  return 'fail'
}

export function normalizeReviewLevel(level?: ReviewLevel | string | null, score?: number | null): ReviewLevel {
  if (level === 'EXCELLENT') return 'excellent'
  if (level === 'GOOD') return 'good'
  if (level === 'NEEDS_IMPROVEMENT') return 'warning'
  if (level === 'POOR') return 'fail'
  if (level === 'excellent' || level === 'good' || level === 'warning' || level === 'fail') return level
  return score !== undefined && score !== null ? getLevelByScore(score) : 'fail'
}

// 趋势周期选项
export const PERIOD_OPTIONS = [
  { label: '按周', value: 'week' },
  { label: '按月', value: 'month' },
  { label: '按季', value: 'quarter' },
]
