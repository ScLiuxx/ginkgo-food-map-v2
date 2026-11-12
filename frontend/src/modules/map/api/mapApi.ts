import { httpClient } from '@/shared/api/httpClient'
import type { ApiResponse } from '@/shared/api/api.types'
import type { CatalogFilters } from '@/modules/restaurant'
import { toRestaurantModel, type RestaurantResponse } from '@/modules/restaurant/api/restaurantApi'
import type { RestaurantReadModel } from '@/modules/restaurant/model/restaurant.types'

export interface MapBounds {
  west: number
  east: number
  south: number
  north: number
}
/** 浏览会话中的实际图面视角，与点选定位前的候选查询范围分开保存。 */
export interface MapCamera {
  center: [number, number]
  zoom: number
}
export interface MapPoint {
  kind?: 'restaurant' | 'amap'
  id: string
  name: string
  latitude: number
  longitude: number
  score: number | null
  /** 五分制口碑分，地图展示转换为现有星级输入。 */
  reputationScore?: number | null
  pricePerPerson: number | null
  /** UNKNOWN仅用于临时第三方点位，不把基础地点搜索当作营业状态证明。 */
  businessStatus: 'OPERATING' | 'CLOSED' | 'UNKNOWN'
}
/** 仅用户点击结果时发起地图定位；序号允许重复点击同一家再次居中。 */
export interface MapFocus {
  /** 点选列表只定位展示，不把门店局部视野重新当成本轮候选范围。 */
  preserveResults?: boolean
  /** 切换校区用区域视野，点选商家仍默认放大到门店。 */
  zoom?: number
  longitude: number
  latitude: number
  sequence: number
}
export interface MapPoints {
  points: MapPoint[]
  /** 与点位同序的完整卡片集合，不能另查目录分页来拼接本轮结果。 */
  records: RestaurantReadModel[]
  matchedCount: number
  returnedCount: number
  limit: number
  truncated: boolean
  search?: { engine: 'ES' | 'MYSQL'; effectiveSort: string }
}

/** 请求显式快照及取消信号；地图点位不携带列表分页，保留服务端上限及计数。 */
export async function fetchMapPoints(
  filters: CatalogFilters,
  bounds: MapBounds,
  signal?: AbortSignal,
): Promise<MapPoints> {
  const response = await httpClient.get<
    ApiResponse<Omit<MapPoints, 'records'> & { records: RestaurantResponse[] }>
  >('/api/restaurants/map-points', {
    params: {
      ...filters,
      ...bounds,
      maxPrice: filters.maxPrice ?? undefined,
      maxDistanceMeters: filters.maxDistanceMeters ?? undefined,
    },
    signal,
  })
  return {
    ...response.data.data,
    records: response.data.data.records.map(toRestaurantModel),
    points: response.data.data.points.map((point) => ({
      ...point,
      score:
        point.reputationScore === undefined
          ? point.score
          : point.reputationScore === null
            ? null
            : point.reputationScore * 2,
    })),
  }
}
