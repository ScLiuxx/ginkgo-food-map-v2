import { describe, expect, it } from 'vitest'
import { campusDistance, filterRestaurants, priceLabel } from './catalog.logic'
import { restaurantFixture } from '../api/restaurant.test-data'
import type { CatalogFilters, RestaurantReadModel } from './restaurant.types'

/** 使用独立测试夹具验证筛选逻辑，不依赖旧项目商户快照。 */
function candidate(
  id: string,
  score: number | null,
  overrides: Partial<RestaurantReadModel> = {},
): RestaurantReadModel {
  return {
    ...restaurantFixture,
    id,
    name: id,
    score,
    reviewCount: score === null ? 0 : 1,
    ...overrides,
  }
}
function filters(overrides: Partial<CatalogFilters> = {}): CatalogFilters {
  return {
    keyword: '',
    category: '',
    sort: 'score',
    hideClosed: false,
    maxPrice: null,
    maxDistanceMeters: null,
    ...overrides,
  }
}

describe('restaurant reading rules', () => {
  it('searches across name, address, category and tags without case sensitivity', () => {
    const items = [
      candidate('a', null, {
        name: '科大面馆',
        address: '建设路',
        category: '面馆',
        tags: ['Wi-Fi'],
      }),
    ]
    for (const keyword of [' 科大 ', '建设路', '面馆', 'wi-fi']) {
      expect(filterRestaurants(items, filters({ keyword }))).toHaveLength(1)
    }
    expect(filterRestaurants(items, filters({ keyword: '找不到' }))).toEqual([])
  })

  it('combines category, keyword and closure filters', () => {
    const items = [
      candidate('a', 8, { category: '火锅', name: '建设路火锅', closed: true }),
      candidate('b', 8, { category: '火锅', name: '建设路火锅二店' }),
      candidate('c', 9, { category: '面馆' }),
    ]
    expect(
      filterRestaurants(
        items,
        filters({ category: '火锅', keyword: '建设路', hideClosed: true }),
      ).map((r) => r.id),
    ).toEqual(['b'])
  })

  it('sorts unknown values last and uses stable IDs for identical names', () => {
    const items = [
      candidate('unknown', null, { price: null }),
      candidate('b', 8, { name: '同名', price: 20 }),
      candidate('a', 8, { name: '同名', price: 20 }),
    ]
    expect(filterRestaurants(items, filters()).map((r) => r.id)).toEqual(['a', 'b', 'unknown'])
    expect(filterRestaurants(items, filters({ sort: 'price' })).map((r) => r.id)).toEqual([
      'a',
      'b',
      'unknown',
    ])
  })

  it('sorts by recommendation or total review count independently from the overall score', () => {
    const items = [
      candidate('a', 9, {
        reviewCount: 1,
        recommendRate: 0.2,
        dimensions: { taste: 5, value: null, service: null, ambience: null },
      }),
      candidate('b', 6, {
        reviewCount: 5,
        recommendRate: 0.9,
        dimensions: { taste: 9, value: null, service: null, ambience: null },
      }),
    ]
    for (const sort of ['recommend', 'reviews'] as const) {
      expect(filterRestaurants(items, filters({ sort })).map((r) => r.id)).toEqual(['b', 'a'])
    }
  })

  it('does not invent a distance for missing, unconfirmed or invalid coordinates', () => {
    const campus = candidate('campus', null, {
      latitude: 30.7544, longitude: 103.9312, needsLocation: false, distanceKm: 0,
    })
    expect(campusDistance(campus, [103.9312, 30.7544])).toBe(0)
    expect(campusDistance({ ...campus, latitude: null }, [103.9312, 30.7544])).toBeNull()
    expect(campusDistance({ ...campus, needsLocation: true }, [103.9312, 30.7544])).toBeNull()
    expect(campusDistance({ ...campus, latitude: NaN }, [103.9312, 30.7544])).toBeNull()
    expect(campusDistance({ ...campus, latitude: 91 }, [103.9312, 30.7544])).toBeNull()
    const items = [
      candidate('far', null, { latitude: 30.65, longitude: 104.08, needsLocation: false, distanceKm: 20 }),
      campus,
      candidate('unknown', null, { needsLocation: true }),
    ]
    expect(filterRestaurants(items, filters({ sort: 'distance' })).map((r) => r.id)).toEqual([
      'campus',
      'far',
      'unknown',
    ])
  })

  it('labels a legacy price estimate separately from an exact or missing price', () => {
    expect(priceLabel(candidate('a', null, { price: 25, priceKind: 'estimate' }))).toBe('约 ¥25')
    expect(priceLabel(candidate('a', null, { price: 25, priceKind: 'exact' }))).toBe('¥25')
    expect(priceLabel(candidate('a', null, { price: null, priceKind: 'unknown' }))).toBe('人均待补')
  })
})
