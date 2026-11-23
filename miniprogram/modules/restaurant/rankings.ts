import { absoluteUrl } from '../../config/env'
import { request } from '../../shared/api/request'
import { isLongId } from '../../shared/lib/id'

export type RankingCampus = 'QINGSHUIHE' | 'SHAHE'
export type RankingPeriod = 'WEEK' | 'MONTH'

interface RankingItemRaw {
  rank: number
  restaurantId: string
  name: string
  coverUrl: string | null
  tasteScore: number
  recommendationScore: number
  participantCount: number
}

interface RankingRaw {
  campusCode: RankingCampus
  periodType: RankingPeriod
  status: 'READY' | 'PREPARING' | 'UNAVAILABLE'
  scoreScale: 5
  windowStartAt: string | null
  windowEndAt: string | null
  generatedAt: string | null
  items: RankingItemRaw[]
}

/** 榜单卡片视图；分数已在服务端按五分制保留一位，客户端不再换算。 */
export interface RankingItemView {
  rank: number
  restaurantId: string
  name: string
  coverUrl: string
  scoreText: string
  tasteText: string
  participantText: string
}

export interface RankingView {
  campusCode: RankingCampus
  periodType: RankingPeriod
  status: 'READY' | 'PREPARING' | 'UNAVAILABLE'
  windowText: string
  items: RankingItemView[]
}

function formatScore(value: number): string {
  return Number.isFinite(value) ? value.toFixed(1) : '—'
}

function formatWindow(start: string | null, end: string | null): string {
  if (!start || !end) return ''
  const startTime = Date.parse(start)
  const endTime = Date.parse(end)
  if (!Number.isFinite(startTime) || !Number.isFinite(endTime)) return ''
  const format = (time: number) => {
    const date = new Date(time)
    const month = `${date.getMonth() + 1}`.padStart(2, '0')
    const day = `${date.getDate()}`.padStart(2, '0')
    return `${date.getFullYear()}.${month}.${day}`
  }
  return `${format(startTime)} – ${format(endTime)}`
}

/**
 * 校验榜单快照并生成视图。
 *
 * 三种状态都可能是成功响应：PREPARING 表示尚无快照，UNAVAILABLE 表示快照过期，
 * 不能把它们降级成空榜或伪造名次；条目顺序完全沿用服务端快照。
 */
function toRankingView(
  raw: RankingRaw,
  campus: RankingCampus,
  period: RankingPeriod,
): RankingView {
  if (
    !raw ||
    raw.campusCode !== campus ||
    raw.periodType !== period ||
    (raw.status !== 'READY' && raw.status !== 'PREPARING' && raw.status !== 'UNAVAILABLE') ||
    raw.scoreScale !== 5 ||
    !Array.isArray(raw.items) ||
    raw.items.length > 20
  ) {
    throw new Error('推荐榜无法确认，请重新加载。')
  }
  const seenRanks = new Set<number>()
  const seenIds = new Set<string>()
  const items = raw.items.map((item): RankingItemView => {
    if (
      !item ||
      !Number.isSafeInteger(item.rank) ||
      item.rank < 1 ||
      seenRanks.has(item.rank) ||
      !isLongId(item.restaurantId) ||
      seenIds.has(item.restaurantId) ||
      typeof item.name !== 'string' ||
      !item.name.trim() ||
      (item.coverUrl !== null && typeof item.coverUrl !== 'string') ||
      !Number.isFinite(item.tasteScore) ||
      item.tasteScore < 0 ||
      item.tasteScore > 5 ||
      !Number.isFinite(item.recommendationScore) ||
      item.recommendationScore < 0 ||
      item.recommendationScore > 5 ||
      !Number.isSafeInteger(item.participantCount) ||
      item.participantCount < 1
    ) {
      throw new Error('推荐榜无法确认，请重新加载。')
    }
    seenRanks.add(item.rank)
    seenIds.add(item.restaurantId)
    return {
      rank: item.rank,
      restaurantId: item.restaurantId,
      name: item.name,
      coverUrl:
        typeof item.coverUrl === 'string' && item.coverUrl ? absoluteUrl(item.coverUrl) : '',
      scoreText: formatScore(item.recommendationScore),
      tasteText: `口味 ${formatScore(item.tasteScore)}`,
      participantText: `${item.participantCount} 人评价`,
    }
  })
  if (raw.status !== 'READY' && items.length > 0) {
    throw new Error('推荐榜无法确认，请重新加载。')
  }
  return {
    campusCode: raw.campusCode,
    periodType: raw.periodType,
    status: raw.status,
    windowText: formatWindow(raw.windowStartAt, raw.windowEndAt),
    items,
  }
}

/** 榜单公开读取；参数固定为校区与周期，不能由客户端控制条数或排序。 */
export async function fetchRestaurantRankings(
  campus: RankingCampus,
  period: RankingPeriod,
): Promise<RankingView> {
  const raw = await request<RankingRaw>({
    path: `/api/restaurant-rankings?campusCode=${campus}&periodType=${period}`,
    auth: 'none',
  })
  return toRankingView(raw, campus, period)
}
