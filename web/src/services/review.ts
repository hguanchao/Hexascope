/*
 * 文件说明：前端接口服务封装，集中管理与后端接口的请求和响应处理。
 */
import request from './request'
import type {
  CreateRequirementRequest,
  PageResult,
  ReviewDetail,
  ReviewListItem,
  ReviewQuery,
  RetriggerRequest,
  UpdateStatusRequest,
} from '../types'

// 获取需求列表
export function getReviewList(params: ReviewQuery): Promise<PageResult<ReviewListItem>> {
  return request.get('/requirements', { params })
}

// 获取需求详情
export function getReviewDetail(id: string): Promise<ReviewDetail> {
  return request.get(`/requirements/${id}`)
}

// 创建需求并同步完成评分
export function createRequirement(data: CreateRequirementRequest): Promise<ReviewListItem> {
  return request.post('/requirements', data)
}

// 重新触发需求审查
export function retriggerReview(data: RetriggerRequest): Promise<ReviewListItem> {
  return request.post('/requirements/retrigger', data)
}

// 更新需求审查状态
export function updateReviewStatus(
  id: string,
  data: UpdateStatusRequest,
): Promise<{ id: string; status: string }> {
  return request.post(`/requirements/${id}/status`, data)
}
