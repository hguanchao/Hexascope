/*
 * 文件说明：前端接口服务封装，集中管理与后端接口的请求和响应处理。
 */
import request from './request'
import type { StatsData, TeamStats, TrendPoint } from '../types'

// 获取团队统计
export function getTeamStats(params: {
  teamId?: string
  startTime?: string
  endTime?: string
}): Promise<TeamStats> {
  return request.get('/reviews/stats/team', { params })
}

// 获取趋势统计
export function getTrendStats(params: {
  teamId?: string
  period: 'week' | 'month' | 'quarter'
}): Promise<TrendPoint[]> {
  return request.get('/reviews/stats/trend', { params })
}

// 获取概览统计（含评分分布和团队排名）
export function getOverviewStats(params?: {
  teamId?: string
  startTime?: string
  endTime?: string
}): Promise<StatsData> {
  return request.get('/reviews/stats/overview', { params })
}
