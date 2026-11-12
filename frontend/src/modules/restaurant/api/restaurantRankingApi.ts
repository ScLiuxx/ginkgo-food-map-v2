import type { ApiResponse } from '@/shared/api/api.types'
import { httpClient } from '@/shared/api/httpClient'

export type RankingCampus = 'QINGSHUIHE' | 'SHAHE'
export type RankingPeriod = 'WEEK' | 'MONTH'
export type RankingStatus = 'READY' | 'PREPARING' | 'UNAVAILABLE'

/** 榜单已是五分制，不能使用会再除以2的通用formatRating。ID始终保留字符串。 */
export interface RestaurantRankingItem {
  rank: number
  restaurantId: string
  name: string
  coverUrl: string | null
  tasteScore: number
  /** 与本周期排名一致的平滑分，不能用长期分或口味均分代替。 */
  recommendationScore: number
  participantCount: number
}

export interface RestaurantRanking {
  campusCode: RankingCampus
  periodType: RankingPeriod
  status: RankingStatus
  scoreScale: 5
  windowStartAt: string | null
  windowEndAt: string | null
  generatedAt: string | null
  items: RestaurantRankingItem[]
}

export type RankingLoader = (
  campusCode: RankingCampus,
  periodType: RankingPeriod,
  signal: AbortSignal,
) => Promise<RestaurantRanking>

function validId(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^[1-9]\d{0,18}$/u.test(value) &&
    BigInt(value) <= 9223372036854775807n
  )
}

/** 只消费同一餐厅的本站正式照片路径，不把响应中的外部地址作为图片请求目标。 */
function safeCoverUrl(value: string | null, restaurantId: string): string | null {
  const match =
    typeof value === 'string'
      ? /^\/api\/restaurants\/([1-9]\d*)\/photos\/([1-9]\d*)$/u.exec(value)
      : null
  return match?.[1] === restaurantId && validId(match[2]) ? value : null
}

/** 精确请求一份榜单，不为各项补查详情或相册，校区切换由调用方取消旧请求。 */
export const fetchRestaurantRanking: RankingLoader = async (campusCode, periodType, signal) => {
  const result = (
    await httpClient.get<ApiResponse<RestaurantRanking>>('/api/restaurant-rankings', {
      params: { campusCode, periodType },
      signal,
    })
  ).data.data
  if (
    !result ||
    result.campusCode !== campusCode ||
    result.periodType !== periodType ||
    !['READY', 'PREPARING', 'UNAVAILABLE'].includes(result.status) ||
    result.scoreScale !== 5 ||
    !Array.isArray(result.items) ||
    result.items.length > 20 ||
    (result.status !== 'READY' && result.items.length !== 0)
  )
    throw new Error('榜单数据格式不正确')

  const times = [result.windowStartAt, result.windowEndAt, result.generatedAt]
  if (
    result.status === 'PREPARING'
      ? times.some((value) => value !== null)
      : times.some((value) => typeof value !== 'string' || !Number.isFinite(Date.parse(value)))
  )
    throw new Error('榜单时间格式不正确')

  const ids = new Set<string>()
  const items = result.items.map((item, index) => {
    if (
      !item ||
      item.rank !== index + 1 ||
      !validId(item.restaurantId) ||
      ids.has(item.restaurantId) ||
      typeof item.name !== 'string' ||
      !item.name.trim() ||
      !Number.isFinite(item.tasteScore) ||
      item.tasteScore < 4 ||
      item.tasteScore > 5 ||
      !Number.isFinite(item.recommendationScore) ||
      item.recommendationScore < 0 ||
      item.recommendationScore > 5 ||
      !Number.isSafeInteger(item.participantCount) ||
      item.participantCount < 3
    )
      throw new Error('榜单内容格式不正确')
    ids.add(item.restaurantId)
    // 仅投影公开展示字段，不保存或展示内部W及规则证据。
    return {
      rank: item.rank,
      restaurantId: item.restaurantId,
      name: item.name,
      coverUrl: safeCoverUrl(item.coverUrl, item.restaurantId),
      tasteScore: item.tasteScore,
      recommendationScore: item.recommendationScore,
      participantCount: item.participantCount,
    }
  })
  return {
    campusCode,
    periodType,
    status: result.status,
    scoreScale: 5,
    windowStartAt: result.windowStartAt,
    windowEndAt: result.windowEndAt,
    generatedAt: result.generatedAt,
    items,
  }
}
