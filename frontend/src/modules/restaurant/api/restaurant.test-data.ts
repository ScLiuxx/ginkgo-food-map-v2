import type { RestaurantPage, RestaurantResponse } from './restaurantApi'
import type { RestaurantReadModel } from '../model/restaurant.types'

/** 仅供测试的响应夹具，故意保留超JS安全整数的字符串ID与未知值。 */
export const responseFixture: RestaurantResponse = {
  id: '9007199254740993',
  name: '接口返回的餐厅',
  categoryCode: 'HOT_POT',
  categoryName: '火锅',
  pricePerPerson: null,
  priceKind: 'UNKNOWN',
  address: null,
  latitude: null,
  longitude: null,
  needsLocation: true,
  businessStatus: 'OPERATING',
  distanceKm: null,
  tags: ['联调模拟'],
  score: null,
  recommendRate: null,
  reviewCount: 0,
  participantCount: 0,
  dimensions: { taste: null, value: null, service: null, ambience: null },
}

export const restaurantFixture: RestaurantReadModel = {
  id: responseFixture.id,
  name: responseFixture.name,
  category: '火锅',
  price: null,
  priceKind: 'unknown',
  address: '',
  latitude: null,
  longitude: null,
  needsLocation: true,
  closed: false,
  distanceKm: null,
  tags: ['联调模拟'],
  score: null,
  recommendRate: null,
  reviewCount: 0,
  participantCount: 0,
  dimensions: responseFixture.dimensions,
}

export const categoryFixtures = [{ code: 'HOT_POT', name: '火锅', sortOrder: 1, enabled: true }]

/** 总数来自服务器，不能通过当前页8条记录推导。 */
export function pageFixture(overrides: Partial<RestaurantPage> = {}): RestaurantPage {
  return {
    page: 1,
    pageSize: 8,
    total: 25,
    totalPages: 4,
    records: Array.from({ length: 8 }, (_, index) => ({
      ...restaurantFixture,
      id: String(index + 1),
      name: `接口餐厅${index + 1}`,
    })),
    ...overrides,
  }
}
