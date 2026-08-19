/*
 * 文件说明：前端状态管理模块，维护跨页面共享的评审筛选和列表状态。
 */
import { create } from 'zustand'
import type { ReviewDetail, ReviewListItem, ReviewQuery } from '../types'
import { getReviewDetail, getReviewList } from '../services/review'

interface ReviewState {
  // 列表数据
  list: ReviewListItem[]
  total: number
  loading: boolean
  // 查询参数
  query: ReviewQuery
  // 详情
  detail: ReviewDetail | null
  detailLoading: boolean

  // Actions
  fetchList: () => Promise<void>
  fetchDetail: (id: string) => Promise<void>
  setQuery: (partial: Partial<ReviewQuery>) => void
  resetQuery: () => void
  setPage: (page: number, pageSize?: number) => void
}

const DEFAULT_QUERY: ReviewQuery = {
  page: 1,
  pageSize: 10,
  sortBy: 'completedAt',
  sortOrder: 'desc',
}

export const useReviewStore = create<ReviewState>((set, get) => ({
  list: [],
  total: 0,
  loading: false,
  query: { ...DEFAULT_QUERY },
  detail: null,
  detailLoading: false,

  fetchList: async () => {
    const { query } = get()
    set({ loading: true })
    try {
      const result = await getReviewList(query)
      set({ list: result.items, total: result.total, loading: false })
    } catch {
      set({ loading: false })
    }
  },

  fetchDetail: async (id: string) => {
    set({ detailLoading: true })
    try {
      const result = await getReviewDetail(id)
      set({ detail: result, detailLoading: false })
    } catch {
      set({ detailLoading: false })
    }
  },

  setQuery: (partial) => {
    set((state) => ({ query: { ...state.query, ...partial } }))
  },

  resetQuery: () => {
    set({ query: { ...DEFAULT_QUERY } })
  },

  setPage: (page, pageSize) => {
    set((state) => ({
      query: {
        ...state.query,
        page,
        pageSize: pageSize ?? state.query.pageSize,
      },
    }))
  },
}))
