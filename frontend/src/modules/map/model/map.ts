import type { RestaurantReadModel } from '@/modules/restaurant'

/** 只把目的地交给高德；统一地点入口兼容电脑和手机，不固定起点或在本站规划路线。 */
export function placeNavigationUrl(place: {
  longitude: number
  latitude: number
  name: string
}): string | null {
  if (
    !Number.isFinite(place.longitude) ||
    !Number.isFinite(place.latitude) ||
    place.longitude < 73 ||
    place.longitude > 136 ||
    place.latitude < 3 ||
    place.latitude > 54
  )
    return null
  return `https://uri.amap.com/marker?${new URLSearchParams({ position: `${place.longitude},${place.latitude}`, name: place.name, coordinate: 'gaode', src: 'ginkgo', callnative: '1' })}`
}

/** 导航只使用已确认的GCJ-02坐标；示例餐厅不允许进入真实导航。 */
export function navigationUrl(restaurant: RestaurantReadModel): string | null {
  const { latitude, longitude } = restaurant
  if (
    restaurant.id.startsWith('demo-') ||
    restaurant.needsLocation ||
    latitude === null ||
    longitude === null ||
    !Number.isFinite(latitude) ||
    !Number.isFinite(longitude) ||
    latitude < 3 ||
    latitude > 54 ||
    longitude < 73 ||
    longitude > 136
  )
    return null
  return placeNavigationUrl({ longitude, latitude, name: restaurant.name })
}

/** 仅用于独立界面预览，不发送接口、不持久化，也不冒充真实店铺。 */
export const mapExamples: RestaurantReadModel[] = [
  ['demo-1', '示例川菜馆', '川菜', 28, 8.6, 412, 0.85, ['川菜', '家常菜', '校园周边']],
  ['demo-2', '示例火锅店', '火锅', 45, 8.2, 320, 1.2, ['火锅', '川味', '聚餐']],
  ['demo-3', '示例面馆', '小吃', 18, 7.9, 186, 1.5, ['小吃', '面食', '快餐']],
].map(([id, name, category, price, score, count, distance, tags]) => ({
  id: String(id),
  name: String(name),
  category: String(category),
  price: Number(price),
  score: Number(score),
  reviewCount: Number(count),
  participantCount: Number(count),
  distanceKm: Number(distance),
  tags: tags as string[],
  address: '示意地址 · 非真实经营资料',
  latitude: null,
  longitude: null,
  needsLocation: true,
  closed: false,
  priceKind: 'exact',
  dimensions: { taste: null, value: null, service: null, ambience: null },
  recommendRate: null,
  source: 'legacy-preview',
}))
