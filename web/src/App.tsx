/*
 * 文件说明：前端应用路由入口，定义页面导航与整体路由结构。
 */
import { Routes, Route, Navigate } from 'react-router-dom'
import MainLayout from './components/Layout'
import Dashboard from './pages/Dashboard'
import ReviewList from './pages/ReviewList'
import ReviewDetail from './pages/ReviewDetail'
import KnowledgeBase from './pages/KnowledgeBase'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<MainLayout />}>
        <Route index element={<Navigate to="/dashboard" replace />} />
        <Route path="dashboard" element={<Dashboard />} />
        <Route path="requirements" element={<ReviewList />} />
        <Route path="requirements/:id" element={<ReviewDetail />} />
        <Route path="knowledge-base" element={<KnowledgeBase />} />
        <Route path="*" element={<Navigate to="/dashboard" replace />} />
      </Route>
    </Routes>
  )
}
