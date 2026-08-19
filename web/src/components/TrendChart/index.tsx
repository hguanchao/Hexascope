/*
 * 文件说明：前端通用组件，封装可复用的展示模块与交互控件。
 */
import ReactECharts from 'echarts-for-react'
import type { EChartsOption } from 'echarts'
import type { TrendPoint } from '../../types'

interface TrendChartProps {
  data: TrendPoint[]
  height?: number
}

export default function TrendChart({ data, height = 320 }: TrendChartProps) {
  const dates = data.map((item) => item.date)
  const scores = data.map((item) => item.avgScore)
  const counts = data.map((item) => item.reviewCount)

  const option: EChartsOption = {
    tooltip: {
      trigger: 'axis',
      axisPointer: {
        type: 'cross',
      },
    },
    legend: {
      data: ['平均分', '审查数量'],
      bottom: 0,
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '12%',
      top: '8%',
      containLabel: true,
    },
    xAxis: {
      type: 'category',
      data: dates,
      boundaryGap: false,
      axisLine: { lineStyle: { color: 'rgba(0,0,0,0.25)' } },
      axisLabel: { color: 'rgba(0,0,0,0.65)' },
    },
    yAxis: [
      {
        type: 'value',
        name: '平均分',
        min: 0,
        max: 100,
        position: 'left',
        axisLine: { lineStyle: { color: '#1890ff' } },
        axisLabel: { formatter: '{value}', color: 'rgba(0,0,0,0.65)' },
        splitLine: { lineStyle: { color: 'rgba(0,0,0,0.06)' } },
      },
      {
        type: 'value',
        name: '审查数量',
        min: 0,
        position: 'right',
        axisLine: { lineStyle: { color: '#faad14' } },
        axisLabel: { formatter: '{value}', color: 'rgba(0,0,0,0.65)' },
        splitLine: { show: false },
      },
    ],
    series: [
      {
        name: '平均分',
        type: 'line',
        smooth: true,
        data: scores,
        yAxisIndex: 0,
        symbolSize: 8,
        lineStyle: { width: 3, color: '#1890ff' },
        itemStyle: { color: '#1890ff' },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(24,144,255,0.25)' },
              { offset: 1, color: 'rgba(24,144,255,0.01)' },
            ],
          },
        },
        markLine: {
          silent: true,
          data: [
            {
              yAxis: 70,
              lineStyle: { color: '#52c41a', type: 'dashed' },
              label: { formatter: '合格线 70', position: 'end', color: '#52c41a' },
            },
          ],
        },
      },
      {
        name: '审查数量',
        type: 'bar',
        data: counts,
        yAxisIndex: 1,
        barWidth: 16,
        itemStyle: { color: 'rgba(250,173,20,0.6)', borderRadius: [4, 4, 0, 0] },
      },
    ],
  }

  return (
    <ReactECharts
      option={option}
      style={{ height, width: '100%' }}
      opts={{ renderer: 'svg' }}
    />
  )
}
