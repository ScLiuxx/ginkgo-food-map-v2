import { createPinia } from 'pinia'
import { createApp, type App } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'
import MapRestaurantCard from './MapRestaurantCard.vue'
import { restaurantFixture } from '../api/restaurant.test-data'
import type { RestaurantReadModel } from '../model/restaurant.types'

/** 改布局不更改事实：未知指标不变成0，内部标签不回流主界面或修改原始记录。 */
describe('地图商户分栏信息', () => {
  let app: App, host: HTMLDivElement
  afterEach(() => {
    app?.unmount()
    host?.remove()
  })
  function mount(restaurant: RestaurantReadModel) {
    host = document.createElement('div')
    document.body.append(host)
    app = createApp(MapRestaurantCard, { restaurant, selected: false }).use(createPinia())
    app.mount(host)
  }
  it('评分和消费明确分栏，未知值保持文字，内部来源标签不展示或改写', () => {
    const row = {
      ...restaurantFixture,
      category: '其他',
      tags: ['OSM测试数据', '联调模拟', 'ODbL-1.0'],
    }
    mount(row)
    expect(host.querySelectorAll('.map-metric-label')).toHaveLength(2)
    expect(host.querySelector('.map-rating')?.textContent).toContain('暂无评分')
    expect(host.querySelector('.map-price')?.textContent).toContain('暂无消费信息')
    expect(host.querySelector('.map-score, .map-facts')).toBeNull()
    expect(host.innerHTML).not.toMatch(/测试数据|联调模拟|ODbL/)
    expect(row.tags).toEqual(['OSM测试数据', '联调模拟', 'ODbL-1.0'])
  })
  it('保留真实业务标签、地址、点评量和估值，不用数据来源代替商户信息', () => {
    mount({
      ...restaurantFixture,
      score: 8.5,
      reviewCount: 12,
      price: 25,
      priceKind: 'estimate',
      category: '川菜',
      address: '校园南路10号',
      tags: ['川菜', '家常菜', '测试数据'],
      distanceKm: 0.5,
    })
    expect(host.querySelector('.map-score')?.textContent).toContain('8.5')
    expect(host.querySelector('.map-review-count')?.textContent).toContain('12 条点评')
    expect(host.querySelector('.map-price-value')?.textContent).toContain('约 ¥25')
    expect(host.querySelector('.map-price-kind')?.textContent).toContain('估值')
    expect(host.querySelector('.map-facts')?.textContent).toContain('家常菜')
    expect(host.querySelector('.map-address')?.textContent).toBe('校园南路10号')
    expect(host.textContent).not.toContain('测试数据')
  })
  it('地图列表和浮层使用目录返回的正式封面地址', () => {
    mount({ ...restaurantFixture, coverPhoto: { id: '801', url: '/api/restaurants/1/photos/801' } })
    expect(host.querySelector('.dish-placeholder img')?.getAttribute('src')).toBe(
      '/api/restaurants/1/photos/801',
    )
    expect(host.querySelector('.dish-placeholder img')?.getAttribute('alt')).toBe('商户相册封面')
  })
})
