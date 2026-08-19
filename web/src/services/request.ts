/*
 * 文件说明：前端接口服务封装，集中管理与后端接口的请求和响应处理。
 */
import axios, { type AxiosInstance, type AxiosResponse } from 'axios'
import { message } from 'antd'
import type { ApiResponse } from '../types'
import { API_BASE_URL } from '../utils/constants'

const request: AxiosInstance = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
})

// 请求拦截器
request.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error),
)

// 响应拦截器：统一处理 ApiResponse 包装
request.interceptors.response.use(
  (response: AxiosResponse<ApiResponse>) => {
    const res = response.data
    if (res.code !== 0) {
      message.error(res.message || '请求失败')
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    return res.data as never
  },
  (error) => {
    if (error.response) {
      const { status, data } = error.response
      const msg = data?.message || `请求错误 (${status})`
      message.error(msg)
    } else if (error.request) {
      message.error('网络异常，请检查网络连接')
    } else {
      message.error(error.message || '请求失败')
    }
    return Promise.reject(error)
  },
)

export default request
