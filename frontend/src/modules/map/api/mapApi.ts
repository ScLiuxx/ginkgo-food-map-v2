import { httpClient } from '@/shared/api/httpClient'
import type { ApiResponse } from '@/shared/api/api.types'
import type { CatalogFilters } from '@/modules/restaurant'

export interface MapBounds {
  west: number
  east: number
  south: number
  north: number
}
export interface MapPoint {
  kind?: 'restaurant' | 'amap'
  id: string
  name: string
  latitude: number
  longitude: number
  score: number | null
  pricePerPerson: number | null
  /** UNKNOWN仅用于临时第三方点位，不把基础地点搜索当作营业状态证明。 */
  businessStatus: 'OPERATING' | 'CLOSED' | 'UNKNOWN'
}
/** 仅用户点击结果时发起地图定位；序号允许重复点击同一家再次居中。 */
export interface MapFocus {
  longitude: number
  latitude: number
  sequence: number
}
export interface MapPoints {
  points: MapPoint[]
  matchedCount: number
  returnedCount: number
  limit: number
  truncated: boolean
}

/** 请求显式快照及取消信号；地图点位不携带列表分页，保留服务端上限及计数。 */
export async function fetchMapPoints(
  filters: CatalogFilters,
  bounds: MapBounds,
  signal?: AbortSignal,
): Promise<MapPoints> {
  const response = await httpClient.get<ApiResponse<MapPoints>>('/api/restaurants/map-points', {
    params: {
      ...filters,
      ...bounds,
      maxPrice: filters.maxPrice ?? undefined,
      maxDistanceMeters: filters.maxDistanceMeters ?? undefined,
    },
    signal,
  })
  return response.data.data
}
