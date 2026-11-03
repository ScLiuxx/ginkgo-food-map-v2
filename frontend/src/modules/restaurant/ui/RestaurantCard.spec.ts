import { createPinia } from 'pinia'
import { createApp, nextTick, ref, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RestaurantCard from './RestaurantCard.vue'
import { restaurantFixture } from '../api/restaurant.test-data'
import type { RestaurantReadModel } from '../model/restaurant.types'

vi.mock('@/modules/review', () => ({
  useRestaurantReviewPreview: () => ({ photo: ref(null), state: ref('empty') }),
}))

/** 首页只使用已知资料，不把图稿中的评分、堂食/外卖能力或窗口记录写成真实数据。 */
describe('商户行的真实资料边界', () => {
  let app: App, host: HTMLDivElement
  afterEach(() => {
    app?.unmount()
    host?.remove()
  })
  async function mount(restaurant: RestaurantReadModel) {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
    })
    await router.push('/')
    host = document.createElement('div')
    document.body.append(host)
    app = createApp(RestaurantCard, { restaurant }).use(router).use(createPinia())
    app.mount(host)
    await nextTick()
  }
  it('未知评分、消费、方式和配图保持未知，不默认推断为非食堂或两种方式都支持', async () => {
    await mount(restaurantFixture)
    expect(host.textContent).toContain('暂无评分')
    expect(host.textContent).toContain('人均待补')
    expect(host.textContent).toContain('用餐方式待补充')
    expect(host.textContent).not.toContain('非食堂商家')
    expect(host.querySelector('img, .score-value')).toBeNull()
  })
  it('食堂只保留真实详情入口，窗口未接通时不生成窗口链接或聚合评分', async () => {
    await mount({ ...restaurantFixture, id: '42', category: '食堂', name: '接口食堂', score: 8.7 })
    expect(host.querySelector('a')?.getAttribute('href')).toBe('/restaurants/42')
    expect(host.textContent).toContain('窗口资料待补充')
    expect(host.textContent).not.toContain('8.7')
    expect(host.querySelector('.card-rating')).toBeNull()
  })
  it('评分、人均、真实业务标签与来源分层显示，保留估值和未知方式说明', async () => {
    await mount({
      ...restaurantFixture,
      score: 8.4,
      price: 25,
      priceKind: 'estimate',
      address: '校园南路10号',
      distanceKm: 1.2,
      tags: ['火锅', '招牌面', '联调模拟'],
    })
    expect(host.querySelector('.score-value')?.textContent).toContain('8.4')
    expect(host.querySelector('.card-price')?.textContent).toContain('约 ¥25')
    expect(host.querySelector('.card-price')?.textContent).toContain('估值')
    expect(host.querySelector('.business-tag')?.textContent).toBe('招牌面')
    expect(host.querySelector('.card-service')?.textContent).not.toContain('联调模拟')
    expect(host.querySelector('.card-provenance')).toBeNull()
    expect(host.textContent).not.toContain('联调模拟')
    expect(host.querySelector('.card-distance')?.textContent).toContain('校园南路10号')
  })
})
