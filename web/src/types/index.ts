/*
 * 文件说明：前端 TypeScript 类型定义，统一声明接口数据和业务模型。
 */
// 审查状态
export type ReviewStatus =
  | 'reviewing'
  | 'pending'
  | 'approved'
  | 'rejected'
  | 'needs_revision'
  | 'review_failed'

// 评分等级
export type ReviewLevel =
  | 'excellent'
  | 'good'
  | 'warning'
  | 'fail'
  | 'EXCELLENT'
  | 'GOOD'
  | 'NEEDS_IMPROVEMENT'
  | 'POOR'

// 评分维度名称
export type DimensionKey =
  | 'completeness'
  | 'clarity'
  | 'feasibility'
  | 'value_alignment'
  | 'testability'
  | 'format'

// 统一响应格式
export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
}

// 分页结果
export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  pageSize: number
}

// 需求列表项
export interface ReviewListItem {
  id: string
  requirementId: string
  requirementTitle: string
  requirementDescription?: string
  priority?: string
  teamId: string
  teamName?: string
  creator: string
  // 异步评分中或评分失败时，后端会返回空分，页面必须按状态展示占位。
  totalScore?: number | null
  level?: ReviewLevel | null
  status: ReviewStatus
  dimensions?: Partial<Record<DimensionKey, number>>
  createdAt: string
  completedAt?: string
}

// 维度评分明细
interface DimensionScore {
  // 未出分时维度分为空，详情页不应把它当成 0 分质量结果。
  score?: number | null
  weight: number
  suggestions: string[]
  evidence?: string[]
  missingItems?: string[]
  scoreReason?: string
  confidence?: number | null
}

// 审查详情
export interface ReviewDetail {
  id: string
  requirementId: string
  requirementTitle: string
  requirementDescription: string
  priority?: string
  requirementUrl?: string
  teamId: string
  teamName?: string
  creator: string
  // 评分工作流完成前，总分和等级允许为空。
  totalScore?: number | null
  level?: ReviewLevel | null
  status: ReviewStatus
  dimensions: Record<DimensionKey, DimensionScore>
  summary?: string
  improvementSuggestion?: string
  createdAt: string
  completedAt?: string
}

// 需求列表查询参数
export interface ReviewQuery {
  page: number
  pageSize: number
  sortBy?: string
  sortOrder?: 'asc' | 'desc'
  minScore?: number
  maxScore?: number
  teamId?: string
  creator?: string
  status?: ReviewStatus
  startTime?: string
  endTime?: string
}

// 团队统计
export interface TeamStats {
  avgScore: number
  passRate: number
  pendingCount: number
  totalReviewed: number
  dimensionAvg: Record<DimensionKey, number>
}

// 趋势数据点
export interface TrendPoint {
  date: string
  avgScore: number
  reviewCount: number
}

// 评分分布
interface ScoreDistribution {
  excellent: number
  good: number
  warning: number
  fail: number
}

// 团队排名
export interface TeamRanking {
  teamId: string
  teamName: string
  avgScore: number
  reviewCount: number
  passRate: number
}

// 概览统计
export interface StatsData {
  avgScore: number
  passRate: number
  pendingCount: number
  totalReviewed: number
  scoreDistribution: ScoreDistribution
  teamRanking: TeamRanking[]
}

// 评分标准导入结果
export interface ScoringRubricImportResult {
  importedCount: number
  skippedCount: number
  version: number
  changed: boolean
  fileName: string
  diffSummary?: KnowledgeImportDiff
}

// 导入行级差异摘要
interface KnowledgeImportDiff {
  added?: string[]
  removed?: string[]
  changed?: string[]
}

// 知识源（导入链版本历史）
export interface KnowledgeSource {
  id: string
  fileName: string
  version: number
  active: boolean
  importedCount: number
  documentCount: number
  createdAt: string
  updatedAt: string
}

// 知识源分页查询参数
export interface KnowledgeSourceQuery {
  page: number
  pageSize: number
}

// 检索追踪记录
export interface KnowledgeTrace {
  id: string
  reviewId?: string
  queries: string[]
  candidates: Array<{ id?: string; score?: number | null }>
  selected: string[]
  retrievalMs?: number
  rerankMs?: number
  totalMs?: number
  hybridEnabled: boolean
  createdAt: string
}

// 检索追踪查询参数
export interface KnowledgeTraceQuery {
  reviewId?: string
  page: number
  pageSize: number
}

// 评估用例
export interface EvalCase {
  id: string
  query: string
  expectedDocIds: string[]
  dimension?: string
  source?: string
  rowNum?: number
  note?: string
  expectedCount: number
  createdAt: string
  updatedAt: string
}

// 评估汇总对比（向量 vs 混合）
export interface EvalSummary {
  caseId: string
  query: string
  dimension?: string
  recallVector?: number
  precisionVector?: number
  mrrVector?: number
  recallHybrid?: number
  precisionHybrid?: number
  mrrHybrid?: number
  executedAt: string
}

// 批量运行评估请求
export interface RunEvalRequest {
  mode?: 'vector' | 'hybrid' | 'all'
  topK?: number
  similarityThreshold?: number
}

// 创建评估用例请求
export interface CreateEvalCaseRequest {
  query: string
  expectedDocIds?: string[]
  dimension?: string
  note?: string
}

// 知识库片段
export interface KnowledgeDocument {
  id: string
  source: string
  dimension: string
  level: string
  severity: string
  row?: number
  content: string
}

// 知识库分页查询参数
export interface KnowledgeDocumentQuery {
  page: number
  pageSize: number
  keyword?: string
  source?: string
}

// 知识库统计
export interface KnowledgeBaseStats {
  total: number
  detailCount: number
  penaltyCount: number
  sources: string[]
}

// 重新审查请求
export interface RetriggerRequest {
  requirementId: string
  reason: string
}

// 创建需求请求
export interface CreateRequirementRequest {
  requirementId: string
  title: string
  description: string
  creator: string
  priority: string
  workspaceId: string
  teamId: string
}

// 更新状态请求
export interface UpdateStatusRequest {
  status: ReviewStatus
}
