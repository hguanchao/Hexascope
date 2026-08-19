/*
 * 文件说明：前端通用组件，封装可复用的展示模块与交互控件。
 */
import ReactECharts from 'echarts-for-react'
import type { EChartsOption } from 'echarts'
import type { DimensionKey } from '../../types'
import { DIMENSION_SCORE_MAX, DIMENSIONS, DIMENSION_LABELS } from '../../utils/constants'

interface RadarChartProps {
  // 各维度得分，0-10
  scores: Record<DimensionKey, number>
  // 各维度平均分（可选，用于对比）
  avgScores?: Record<DimensionKey, number>
  height?: number
}

export default function RadarChart({ scores, avgScores, height = 360 }: RadarChartProps) {
  const indicators = DIMENSIONS.map((key) => ({
    name: DIMENSION_LABELS[key],
    max: DIMENSION_SCORE_MAX,
  }))

  const currentData = DIMENSIONS.map((key) => scores[key] ?? 0)

  const series: Array<{ value: number[]; name: string; areaStyle?: { opacity: number } }> = [
    {
      value: currentData,
      name: '本次评分',
    },
  ]

  if (avgScores) {
    series.push({
      value: DIMENSIONS.map((key) => avgScores[key] ?? 0),
      name: '团队均值',
      areaStyle: { opacity: 0.05 },
    })
  }

  const option: EChartsOption = {
    tooltip: {
      trigger: 'item',
    },
    legend: {
      data: avgScores ? ['本次评分', '团队均值'] : ['本次评分'],
      bottom: 0,
    },
    radar: {
      indicator: indicators,
      shape: 'polygon',
      radius: '65%',
      splitNumber: 5,
      axisName: {
        color: 'rgba(0,0,0,0.65)',
        fontSize: 12,
      },
      splitArea: {
        areaStyle: {
          color: ['rgba(24,144,255,0.02)', 'rgba(24,144,255,0.04)'],
        },
      },
      splitLine: {
        lineStyle: {
          color: 'rgba(0,0,0,0.08)',
        },
      },
      axisLine: {
        lineStyle: {
          color: 'rgba(0,0,0,0.12)',
        },
      },
    },
    series: [
      {
        type: 'radar',
        data: series,
        symbolSize: 6,
        lineStyle: {
          width: 2,
        },
        areaStyle: {
          opacity: 0.2,
        },
      },
    ],
    color: ['#1890ff', '#faad14'],
  }

  return (
    <ReactECharts
      option={option}
      style={{ height, width: '100%' }}
      opts={{ renderer: 'svg' }}
    />
  )
}
