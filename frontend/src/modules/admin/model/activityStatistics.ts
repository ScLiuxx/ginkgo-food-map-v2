export type CollectionStatus = 'NORMAL' | 'PARTIAL' | 'UNKNOWN'
export type ActivityMetric = 'active' | 'new' | 'reviews'

export interface ActivityDay {
  date: string
  activeUserCount: number | null
  newUserCount: number | null
  publishedReviewCount: number | null
  collectionStatus: CollectionStatus
  activeSyncedAt: string | null
}

/** 前端先行约定的读取投影，后端尚待实现；NULL始终保留为未知。 */
export interface ActivityStatistics {
  enabled: boolean
  timezone: 'Asia/Shanghai'
  startedAt: string | null
  currentDate: string
  fetchedAt: string
  sevenDayActiveUserCount: number | null
  sevenDayCollectionStatus: CollectionStatus
  days: ActivityDay[]
}

export interface TrendPoint {
  date: string
  value: number | null
  status: CollectionStatus | 'NOT_COLLECTED'
}

export const activityMetrics = [
  { key: 'active', label: '日活跃', detail: '活跃人数', field: 'activeUserCount', unit: '人' },
  { key: 'new', label: '新增用户', detail: '新增用户', field: 'newUserCount', unit: '人' },
  {
    key: 'reviews',
    label: '发布点评',
    detail: '发布点评',
    field: 'publishedReviewCount',
    unit: '条',
  },
] as const

/** 日期运算以UTC承载自然日标签，避免浏览器本地时区和夏令时改变天数。 */
export function shiftActivityDate(date: string, days: number): string {
  const value = new Date(date + 'T00:00:00Z')
  value.setUTCDate(value.getUTCDate() + days)
  return value.toISOString().slice(0, 10)
}

export function beijingDate(instant: string): string {
  return new Date(Date.parse(instant) + 8 * 60 * 60 * 1000).toISOString().slice(0, 10)
}

export function activityTime(instant: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(instant))
}

export function activityDateLabel(date: string): string {
  return `${Number(date.slice(5, 7))}月${Number(date.slice(8))}日`
}

export const activityNumber = (value: number | null | undefined) =>
  value === null || value === undefined ? '—' : value.toLocaleString('zh-CN')

export function activityTrend(data: ActivityStatistics, metric: ActivityMetric): TrendPoint[] {
  const field = activityMetrics.find((item) => item.key === metric)!.field
  const rows = new Map(data.days.map((row) => [row.date, row]))
  const started = data.startedAt ? beijingDate(data.startedAt) : null
  return Array.from({ length: 30 }, (_, index) => {
    const date = shiftActivityDate(data.currentDate, index - 29)
    const row = rows.get(date)
    return {
      date,
      value: row?.[field] ?? null,
      status: !started || date < started ? 'NOT_COLLECTED' : (row?.collectionStatus ?? 'UNKNOWN'),
    }
  })
}

export interface ChartPoint extends TrendPoint {
  x: number
  y: number | null
  index: number
}

/** 缺失值拆开折线；0仍落在基线上，不被当作空值。 */
export function activityChart(points: TrendPoint[], width: number, height: number) {
  const left = 36,
    right = 10,
    top = 14,
    bottom = height - 34
  const maximum = Math.max(0, ...points.map((point) => point.value ?? 0))
  const base = maximum > 0 ? 10 ** Math.floor(Math.log10(maximum / 4)) : 1
  const raw = maximum / 4 / base
  const step = Math.max(1, (raw <= 1 ? 1 : raw <= 2 ? 2 : raw <= 5 ? 5 : 10) * base)
  const limit = Math.max(step, Math.ceil(maximum / step) * step)
  const coordinates: ChartPoint[] = points.map((point, index) => ({
    ...point,
    index,
    x: left + (index / Math.max(1, points.length - 1)) * (width - left - right),
    y: point.value === null ? null : top + (1 - point.value / limit) * (bottom - top),
  }))
  const segments: ChartPoint[][] = []
  for (const point of coordinates) {
    if (point.y === null) continue
    const previous = segments.at(-1)
    if (previous?.at(-1)?.index === point.index - 1) previous.push(point)
    else segments.push([point])
  }
  return {
    left,
    right,
    top,
    bottom,
    coordinates,
    ticks: Array.from({ length: Math.round(limit / step) + 1 }, (_, index) => ({
      value: index * step,
      y: top + (1 - (index * step) / limit) * (bottom - top),
    })),
    segments: segments.map((segment) => ({
      points: segment,
      line: segment.map((point, index) => `${index ? 'L' : 'M'}${point.x},${point.y}`).join(' '),
      area:
        segment.length < 2
          ? ''
          : `${segment.map((point, index) => `${index ? 'L' : 'M'}${point.x},${point.y}`).join(' ')} L${segment.at(-1)!.x},${bottom} L${segment[0]!.x},${bottom} Z`,
    })),
  }
}
