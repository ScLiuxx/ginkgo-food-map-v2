import { describe, expect, it } from 'vitest'
import { activityChart, activityTrend, beijingDate, shiftActivityDate } from './activityStatistics'
import { activityStatisticsFixture } from '../test/activityStatisticsFixture'

describe('统计日期与趋势', () => {
  it('按北京时间判断跨日，自然日运算跨月和闰日不依赖浏览器时区', () => {
    expect(beijingDate('2026-11-19T15:59:59Z')).toBe('2026-11-19')
    expect(beijingDate('2026-11-19T16:00:00Z')).toBe('2026-11-20')
    expect(shiftActivityDate('2026-01-01', -1)).toBe('2025-12-31')
    expect(shiftActivityDate('2024-03-01', -1)).toBe('2024-02-29')
  })
  it('启用前留空、采集期内缺行保留未知，真实0不丢失', () => {
    const data = activityStatisticsFixture()
    data.startedAt = '2026-11-17T02:00:00Z'
    data.days = [data.days[28]!, { ...data.days[29]!, newUserCount: 0 }]
    const points = activityTrend(data, 'new')
    expect(points).toHaveLength(30)
    expect(points[0]).toEqual({ date: '2026-10-21', value: null, status: 'NOT_COLLECTED' })
    expect(points[27]).toEqual({ date: '2026-11-17', value: null, status: 'UNKNOWN' })
    expect(points[29]?.value).toBe(0)
  })
  it('缺失日期使折线断开，不能跨缺口连线或把空值压到0', () => {
    const geometry = activityChart(
      [
        { date: '2026-11-17', value: 0, status: 'NORMAL' },
        { date: '2026-11-18', value: null, status: 'UNKNOWN' },
        { date: '2026-11-19', value: 12, status: 'PARTIAL' },
      ],
      950,
      310,
    )
    expect(geometry.segments).toHaveLength(2)
    expect(geometry.segments.every((segment) => segment.area === '')).toBe(true)
    expect(geometry.coordinates[0]?.y).toBe(geometry.bottom)
    expect(geometry.coordinates[1]?.y).toBeNull()
  })
  it('全0有有效基线，轴与坐标不会出现NaN或Infinity', () => {
    const geometry = activityChart([{ date: '2026-11-19', value: 0, status: 'NORMAL' }], 250, 235)
    expect(geometry.coordinates[0]?.y).toBe(geometry.bottom)
    expect(geometry.segments[0]?.line).not.toMatch(/NaN|Infinity/)
    expect(geometry.ticks.every((tick) => Number.isInteger(tick.value))).toBe(true)
  })
})
