import type { CatalogFilters } from './restaurant.types'

/** 已确认的GCJ-02校区参考点，与榜单范围保持一致；这里只提供参照，不施加半径筛选。 */
export const campusOrigins = {
  QINGSHUIHE: { label: '清水河', latitude: 30.7544, longitude: 103.9312 },
  SHAHE: { label: '沙河', latitude: 30.675789, longitude: 104.099671 },
} as const

/** 卡片与排序共用参照点名称，不将校区距离标记为距用户位置。 */
export function distanceOriginLabel(origin: CatalogFilters['distanceOrigin']): string {
  return origin && origin !== 'USER' ? campusOrigins[origin].label : '我'
}
