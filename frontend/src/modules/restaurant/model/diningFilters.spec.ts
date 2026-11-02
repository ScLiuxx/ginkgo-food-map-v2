import { describe, expect, it } from 'vitest'
import { matchesDiningFilters } from './diningFilters'
import { restaurantFixture } from '../api/restaurant.test-data'
import type { CatalogFilters } from './restaurant.types'

const filters: CatalogFilters = {
  keyword: '',
  category: '',
  sort: 'score',
  hideClosed: false,
  maxPrice: null,
  maxDistanceMeters: null,
}
describe('餐厅类型与用餐方式组合筛选', () => {
  it('prefers explicit type and supports the old canteen category without name guessing', () => {
    expect(
      matchesDiningFilters(
        { ...restaurantFixture, category: '食堂' },
        { ...filters, restaurantType: 'CANTEEN' },
      ),
    ).toBe(true)
    expect(
      matchesDiningFilters(
        { ...restaurantFixture, category: '食堂', restaurantType: 'REGULAR' },
        { ...filters, restaurantType: 'CANTEEN' },
      ),
    ).toBe(false)
    expect(
      matchesDiningFilters(
        { ...restaurantFixture, name: '食堂风味店', category: '其他' },
        { ...filters, restaurantType: 'CANTEEN' },
      ),
    ).toBe(false)
  })
  it('only matches confirmed service data and combines conditions independently', () => {
    for (const supportsDelivery of [null, undefined, false]) {
      expect(
        matchesDiningFilters(
          { ...restaurantFixture, supportsDelivery },
          { ...filters, diningMode: 'delivery' },
        ),
      ).toBe(false)
    }
    expect(
      matchesDiningFilters(
        { ...restaurantFixture, supportsDelivery: true },
        { ...filters, restaurantType: 'REGULAR', diningMode: 'delivery' },
      ),
    ).toBe(true)
    expect(
      matchesDiningFilters(
        { ...restaurantFixture, category: '食堂', supportsDelivery: true },
        { ...filters, restaurantType: 'REGULAR', diningMode: 'delivery' },
      ),
    ).toBe(false)
    expect(
      matchesDiningFilters(
        { ...restaurantFixture, supportsDineIn: true, supportsDelivery: false },
        { ...filters, diningMode: 'dineIn' },
      ),
    ).toBe(true)
  })
})
