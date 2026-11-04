import { describe, expect, it } from 'vitest'
import { responseFixture } from '../api/restaurant.test-data'
import {
  characterCount,
  restaurantDraft,
  restaurantInput,
  validateRestaurantDraft,
} from './restaurantForm'

const valid = () => ({
  ...restaurantDraft(),
  name: '测试餐厅',
  categoryCode: 'HOT_POT',
  latitude: '30.7544',
  longitude: '103.9312',
  locationConfirmed: true,
})

/** 校验真实业务边界，防止空值、小数金额和未经确认的位置被错误转换后提交。 */
describe('restaurant editor input rules', () => {
  it('食堂无分类也可提交，普通餐厅仍须选分类，食堂请求不携带旧草稿分类', () => {
    const form = { ...valid(), categoryCode: '' }
    expect(validateRestaurantDraft(form, 'CANTEEN')).toEqual({})
    expect(validateRestaurantDraft(form, 'REGULAR')).toHaveProperty('categoryCode')
    expect(restaurantInput(valid(), 'CANTEEN').categoryCode).toBeNull()
  })
  it('requires explicit location without default campus coordinates', () => {
    const form = restaurantDraft()
    expect(form.latitude).toBe('')
    expect(form.longitude).toBe('')
    expect(validateRestaurantDraft(form)).toHaveProperty('locationConfirmed')
    expect(validateRestaurantDraft(valid())).toEqual({})
  })
  it('counts emoji as one Unicode code point for names and addresses', () => {
    expect(characterCount('😀')).toBe(1)
    expect(
      validateRestaurantDraft({ ...valid(), name: '😀'.repeat(40), address: '😀'.repeat(80) }),
    ).toEqual({})
    expect(validateRestaurantDraft({ ...valid(), name: '😀'.repeat(41) })).toHaveProperty('name')
    expect(validateRestaurantDraft({ ...valid(), address: '😀'.repeat(81) })).toHaveProperty(
      'address',
    )
  })
  it.each(['', '0', '5001', '20.5', '1e2', '-3', 'NaN'])(
    'rejects invalid exact integer amount %s',
    (price) => {
      expect(validateRestaurantDraft({ ...valid(), priceKind: 'EXACT', price })).toHaveProperty(
        'price',
      )
    },
  )
  it('preserves price nulls and the explicit full replacement fields', () => {
    const input = restaurantInput({
      ...valid(),
      price: '999',
      address: '  ',
      tags: ' 第一个 \n\n 第二个 ',
    })
    expect(input).toEqual({
      name: '测试餐厅',
      categoryCode: 'HOT_POT',
      priceKind: 'UNKNOWN',
      pricePerPerson: null,
      address: null,
      latitude: 30.7544,
      longitude: 103.9312,
      locationConfirmed: true,
      businessStatus: 'OPERATING',
      tags: ['第一个', '第二个'],
    })
    expect(input).not.toHaveProperty('rowVersion')
  })
  it.each(['30.1234567', 'NaN', '2.9', '54.1', '3e1'])(
    'rejects untrusted latitude %s',
    (latitude) => {
      expect(validateRestaurantDraft({ ...valid(), latitude })).toHaveProperty('latitude')
    },
  )
  it('limits tag count and total code points', () => {
    expect(
      validateRestaurantDraft({ ...valid(), tags: Array(9).fill('标签').join('\n') }),
    ).toHaveProperty('tags')
    expect(validateRestaurantDraft({ ...valid(), tags: '😀'.repeat(81) })).toHaveProperty('tags')
    expect(validateRestaurantDraft({ ...valid(), tags: '😀'.repeat(80) })).not.toHaveProperty(
      'tags',
    )
  })
  it('loads complete editable fields while requiring position reconfirmation', () => {
    const form = restaurantDraft({
      ...responseFixture,
      latitude: 31,
      longitude: 104,
      businessStatus: 'CLOSED',
      priceKind: 'ESTIMATE',
      pricePerPerson: 88,
    })
    expect(form).toMatchObject({
      categoryCode: 'HOT_POT',
      latitude: '31',
      longitude: '104',
      price: '88',
      priceKind: 'ESTIMATE',
      businessStatus: 'CLOSED',
      locationConfirmed: false,
    })
  })
})
