import { ApiError, request } from '../../shared/api/request'

/** 地图点位只读取后端公开投影，不包含地址、标签和相册。 */
export interface MapPoint {
  id: string
  name: string
  latitude: number
  longitude: number
  score: number | null
  pricePerPerson: number | null
  businessStatus: 'OPERATING' | 'CLOSED'
}

export interface MapPointPage {
  points: MapPoint[]
  matchedCount: number
  returnedCount: number
  limit: number
  truncated: boolean
}

function isPoint(value: unknown): value is MapPoint {
  if (!value || typeof value !== 'object') return false
  const row = value as Partial<MapPoint>
  return (
    typeof row.id === 'string' &&
    row.id.length > 0 &&
    typeof row.name === 'string' &&
    typeof row.latitude === 'number' &&
    Number.isFinite(row.latitude) &&
    typeof row.longitude === 'number' &&
    Number.isFinite(row.longitude) &&
    (row.businessStatus === 'OPERATING' || row.businessStatus === 'CLOSED')
  )
}

/**
 * 读取当前视口内的店铺点位。
 *
 * 后端要求四边同时提供，固定最多返回 100 条；响应截断时由页面提示用户放大地图，
 * 不能把“已返回数量”当作当前范围的全部店铺数。
 */
export function fetchMapPoints(bounds: WxMapRegion): Promise<MapPointPage> {
  const query = [
    `west=${bounds.southwest.longitude}`,
    `east=${bounds.northeast.longitude}`,
    `south=${bounds.southwest.latitude}`,
    `north=${bounds.northeast.latitude}`,
  ].join('&')

  return request<unknown>({
    path: `/api/restaurants/map-points?${query}`,
    auth: 'none',
  }).then((body) => {
    if (!body || typeof body !== 'object') {
      throw new ApiError('地图数据格式异常，请稍后重试。', 'INVALID_RESPONSE', 200)
    }
    const row = body as Partial<MapPointPage>
    if (!Array.isArray(row.points)) {
      throw new ApiError('地图数据格式异常，请稍后重试。', 'INVALID_RESPONSE', 200)
    }
    const points = row.points.filter(isPoint).map((point) => ({
      id: point.id,
      name: point.name,
      latitude: point.latitude,
      longitude: point.longitude,
      score: typeof point.score === 'number' ? point.score : null,
      pricePerPerson: typeof point.pricePerPerson === 'number' ? point.pricePerPerson : null,
      businessStatus: point.businessStatus,
    }))
    return {
      points,
      matchedCount:
        typeof row.matchedCount === 'number' && row.matchedCount >= 0 ? row.matchedCount : points.length,
      returnedCount:
        typeof row.returnedCount === 'number' && row.returnedCount >= 0
          ? row.returnedCount
          : points.length,
      limit: typeof row.limit === 'number' && row.limit > 0 ? row.limit : 100,
      truncated: row.truncated === true,
    }
  })
}
