/*
 * 文件说明：前端通用组件，封装可复用的展示模块与交互控件。
 */
import { useState } from 'react'
import { Layout, Menu, theme } from 'antd'
import {
  DashboardOutlined,
  FileSearchOutlined,
  DatabaseOutlined,
} from '@ant-design/icons'
import { useLocation, useNavigate, Outlet } from 'react-router-dom'

const { Header, Sider, Content } = Layout

const menuItems = [
  {
    key: '/dashboard',
    icon: <DashboardOutlined />,
    label: '概览',
  },
  {
    key: '/requirements',
    icon: <FileSearchOutlined />,
    label: '需求列表',
  },
  {
    key: '/knowledge-base',
    icon: <DatabaseOutlined />,
    label: '知识库',
  },
]

export default function MainLayout() {
  const [collapsed, setCollapsed] = useState(false)
  const location = useLocation()
  const navigate = useNavigate()
  const {
    token: { colorBgContainer },
  } = theme.useToken()

  // 根据当前路径计算选中的菜单项（处理子路由）
  const firstPath = location.pathname.split('/')[1]
  const selectedKey = firstPath === 'reviews' ? '/requirements' : `/${firstPath}`

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider
        collapsible
        collapsed={collapsed}
        onCollapse={setCollapsed}
        theme="light"
        width={220}
      >
        <div
          style={{
            height: 56,
            margin: 8,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontWeight: 700,
            fontSize: collapsed ? 14 : 16,
            color: '#1890ff',
            whiteSpace: 'nowrap',
            overflow: 'hidden',
          }}
        >
          {collapsed ? 'RFA' : '需求审查 Agent'}
        </div>
        <Menu
          mode="inline"
          selectedKeys={[selectedKey]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            padding: '0 24px',
            background: colorBgContainer,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            boxShadow: '0 1px 4px rgba(0,21,41,0.08)',
          }}
        >
          <span style={{ fontSize: 15, color: 'rgba(0,0,0,0.65)' }}>
            TAPD 需求审查与评分 AI Agent
          </span>
        </Header>
        <Content
          style={{
            margin: 0,
            minHeight: 280,
            overflow: 'auto',
          }}
        >
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
