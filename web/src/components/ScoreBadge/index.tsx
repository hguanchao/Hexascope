/*
 * 文件说明：前端通用组件，封装可复用的展示模块与交互控件。
 */
import { Tag } from 'antd'
import type { ReviewLevel } from '../../types'
import { normalizeReviewLevel, LEVEL_COLORS, LEVEL_LABELS } from '../../utils/constants'

interface ScoreBadgeProps {
  score?: number | null
  level?: ReviewLevel | null
  showLevel?: boolean
}

export default function ScoreBadge({ score, level, showLevel = true }: ScoreBadgeProps) {
  // 异步评分场景下分数可能暂时为空，此时只展示占位，避免误导为 0 分。
  if (score === undefined || score === null) {
    return (
      <Tag
        style={{
          margin: 0,
          padding: '2px 10px',
          borderRadius: 4,
          fontSize: 14,
          fontWeight: 600,
        }}
      >
        -
      </Tag>
    )
  }

  // 优先使用传入的 level，否则根据 score 计算
  const actualLevel: ReviewLevel = normalizeReviewLevel(level, score)
  const color = LEVEL_COLORS[actualLevel]
  const displayScore = score !== undefined ? score.toFixed(1) : '-'

  return (
    <Tag
      color={color}
      style={{
        margin: 0,
        padding: '2px 10px',
        borderRadius: 4,
        fontSize: 14,
        fontWeight: 600,
        color: '#fff',
        background: color,
        border: 'none',
      }}
    >
      {displayScore}
      {showLevel && (
        <span style={{ marginLeft: 6, fontSize: 12, opacity: 0.9 }}>
          {LEVEL_LABELS[actualLevel]}
        </span>
      )}
    </Tag>
  )
}
