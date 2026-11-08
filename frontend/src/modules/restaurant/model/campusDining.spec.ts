import { describe, expect, it } from 'vitest'
import { canteenRating, compareFloors, diningDetails, type DiningWindow } from './campusDining'

/** 仅验证计算与异常资料边界，不向生产目录放入窗口记录。 */
const window = (id: string, changes: Partial<DiningWindow> = {}): DiningWindow => ({
  id,
  canteenId: 'canteen',
  name: id,
  floor: '二楼',
  score: 8,
  reviewCount: 1,
  price: null,
  closed: false,
  specialties: [],
  ...changes,
})

describe('canteen independent window summary', () => {
  it('averages rated operating windows equally and excludes unknown, closed, foreign, duplicate and invalid entries', () => {
    expect(
      canteenRating('canteen', [
        window('a', { score: 8, reviewCount: 100 }),
        window('b', { score: 6 }),
        window('none', { score: null }),
        window('unreviewed', { reviewCount: 0 }),
        window('closed', { score: 10, closed: true }),
        window('foreign', { canteenId: 'other' }),
        window('a'),
        window('nan', { score: NaN }),
        window('bad', { score: 12 }),
      ]),
    ).toEqual({ score: 7, count: 2 })
  })
  it('allows a window and its parent to share a numeric identity', () => {
    expect(canteenRating('canteen', [window('canteen')])).toEqual({
      score: 8,
      count: 1,
    })
  })
  it('preserves a reviewed zero and shows unrated instead of a parent score for empty/unavailable collections', () => {
    expect(canteenRating('canteen', [window('zero', { score: 0 })])).toEqual({
      score: 0,
      count: 1,
    })
    expect(canteenRating('canteen', null)).toEqual({ score: null, count: 0 })
    expect(canteenRating('canteen', [])).toEqual({ score: null, count: 0 })
    expect(canteenRating('canteen', [window('closed', { closed: true })])).toEqual({
      score: null,
      count: 0,
    })
  })
  it('orders Chinese and numeric floors naturally and respects explicit restaurant types', () => {
    expect(['三楼', '一楼', '二楼'].sort(compareFloors)).toEqual(['一楼', '二楼', '三楼'])
    expect(['10F', '2F', 'B1', '1F'].sort(compareFloors)).toEqual(['B1', '1F', '2F', '10F'])
    expect(diningDetails({ category: '其他', restaurantType: 'CANTEEN' }).kind).toBe('canteen')
    expect(diningDetails({ category: '食堂', restaurantType: 'REGULAR' }).kind).toBe('restaurant')
  })
})
