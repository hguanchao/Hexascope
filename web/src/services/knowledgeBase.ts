/*
 * 文件说明：前端接口服务封装，集中管理与后端接口的请求和响应处理。
 */
import request from './request'
import type {
  CreateEvalCaseRequest,
  EvalCase,
  EvalSummary,
  KnowledgeBaseStats,
  KnowledgeDocument,
  KnowledgeDocumentQuery,
  KnowledgeSource,
  KnowledgeSourceQuery,
  KnowledgeTrace,
  KnowledgeTraceQuery,
  PageResult,
  RunEvalRequest,
  ScoringRubricImportResult,
} from '../types'

// 获取知识库统计
export function getKnowledgeBaseStats(): Promise<KnowledgeBaseStats> {
  return request.get('/knowledge-base/stats')
}

// 获取知识源（版本历史）
export function getKnowledgeSources(params: KnowledgeSourceQuery): Promise<PageResult<KnowledgeSource>> {
  return request.get('/knowledge-base/sources', { params })
}

// 获取检索追踪记录
export function getKnowledgeTraces(params: KnowledgeTraceQuery): Promise<PageResult<KnowledgeTrace>> {
  return request.get('/knowledge-base/traces', { params })
}

// 清理 N 天前的检索追踪记录
export function cleanKnowledgeTraces(olderThanDays = 30): Promise<{ deleted: number }> {
  return request.post('/knowledge-base/traces/clean', null, { params: { olderThanDays } })
}

// 获取评估用例列表
export function getEvalCases(params: {
  page: number
  pageSize: number
  dimension?: string
}): Promise<PageResult<EvalCase>> {
  return request.get('/knowledge-base/eval/cases', { params })
}

// 创建人工评估用例
export function createEvalCase(data: CreateEvalCaseRequest): Promise<void> {
  return request.post('/knowledge-base/eval/cases', data)
}

// 删除评估用例
export function deleteEvalCase(id: string): Promise<void> {
  return request.delete(`/knowledge-base/eval/cases/${id}`)
}

// 批量运行评估用例
export function runAllEval(data?: RunEvalRequest): Promise<{ executed: number }> {
  return request.post('/knowledge-base/eval/run-all', data ?? { mode: 'all' })
}

// 获取评估汇总对比（向量 vs 混合）
export function getEvalSummary(params?: { dimension?: string; limit?: number }): Promise<EvalSummary[]> {
  return request.get('/knowledge-base/eval/summary', { params })
}

// 获取知识片段列表
export function getKnowledgeDocuments(
  params: KnowledgeDocumentQuery,
): Promise<PageResult<KnowledgeDocument>> {
  return request.get('/knowledge-base/documents', { params })
}

// 导入评分标准到 RAG 知识库
export function importKnowledgeBase(file: File): Promise<ScoringRubricImportResult> {
  const formData = new FormData()
  formData.append('file', file)

  return request.post('/knowledge-base/import', formData, {
    headers: {
      'Content-Type': 'multipart/form-data',
    },
    timeout: 120000,
  })
}

// 删除知识片段
export function deleteKnowledgeDocument(id: string): Promise<{ id: string; deleted: boolean }> {
  return request.delete(`/knowledge-base/documents/${id}`)
}
