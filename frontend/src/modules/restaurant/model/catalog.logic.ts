import type { CatalogFilters, RatingDimension, RestaurantReadModel } from './restaurant.types'

export const ratingDimensions: { key: RatingDimension; label: string; description: string }[] = [
  { key: 'taste', label: '味道', description: '调味、火候与食材，吃完还想不想再来。' },
  { key: 'value', label: '性价比', description: '从学生的预算出发，这一餐值不值得。' },
  { key: 'service', label: '服务', description: '态度、出餐速度，以及用餐中的照顾。' },
  { key: 'ambience', label: '环境', description: '卫生、座位与噪音，能否舒服吃顿饭。' },
]

/** 以已授权的GCJ-02用户位置计算直线公里数；无原点时仅使用服务器明确返回的距离。 */
export function campusDistance(
  restaurant: RestaurantReadModel,
  origin?: [number, number],
): number | null {
  if (!origin) return restaurant.distanceKm ?? null
  const { latitude, longitude } = restaurant
  if (
    restaurant.needsLocation ||
    latitude === null ||
    longitude === null ||
    !Number.isFinite(latitude) ||
    !Number.isFinite(longitude) ||
    Math.abs(latitude) > 90 ||
    Math.abs(longitude) > 180
  )
    return null
  const rad = Math.PI / 180
  const dLat = (latitude - origin[1]) * rad
  const dLng = (longitude - origin[0]) * rad
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(origin[1] * rad) * Math.cos(latitude * rad) * Math.sin(dLng / 2) ** 2
  return 6371 * 2 * Math.asin(Math.sqrt(Math.min(1, a)))
}

/** 金额来源同时展示，旧档位估值不能被当成已核实的精确消费。 */
export function priceLabel(restaurant: RestaurantReadModel): string {
  if (restaurant.price === null || restaurant.price <= 0) return '人均待补'
  return `${restaurant.priceKind === 'estimate' ? '约 ' : ''}¥${restaurant.price}`
}

/** 客户端预览筛选：将来由服务端提供分页时替换数据来源，不将当前页误当完整集合。 */
export function filterRestaurants(
  restaurants: RestaurantReadModel[],
  filters: CatalogFilters,
): RestaurantReadModel[] {
  const keyword = filters.keyword.trim().toLocaleLowerCase()
  const result = restaurants.filter((restaurant) => {
    if (filters.hideClosed && restaurant.closed) return false
    if (filters.category && restaurant.category !== filters.category) return false
    if (
      keyword &&
      ![restaurant.name, restaurant.address, restaurant.category, ...restaurant.tags]
        .join(' ')
        .toLocaleLowerCase()
        .includes(keyword)
    )
      return false
    return true
  })
  const value = (restaurant: RestaurantReadModel): number | null => {
    if (filters.sort === 'distance') return campusDistance(restaurant)
    if (filters.sort === 'recommend') return restaurant.recommendRate
    if (filters.sort === 'reviews') return restaurant.reviewCount
    if (filters.sort === 'price')
      return restaurant.price !== null && restaurant.price > 0 ? restaurant.price : null
    return restaurant.score
  }
  return result.sort((left, right) => {
    const a = value(left)
    const b = value(right)
    if (a === null && b !== null) return 1
    if (a !== null && b === null) return -1
    if (a !== null && b !== null && a !== b) {
      return filters.sort === 'price' || filters.sort === 'distance' ? a - b : b - a
    }
    return left.name.localeCompare(right.name, 'zh-CN') || left.id.localeCompare(right.id)
  })
}
