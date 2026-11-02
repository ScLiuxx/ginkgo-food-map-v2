import { createApp } from 'vue'
import type { App } from 'vue'
import { createPinia, disposePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import HomePage from './HomePage.vue'

// 视觉契约不依赖网络；餐厅HTTP行为由独立接入测试覆盖。
vi.mock('@/modules/restaurant/api/restaurantApi', () => ({
  fetchRestaurantCategories: vi.fn().mockResolvedValue([]),
  fetchRestaurants: vi.fn().mockResolvedValue({ records: [], total: 0, totalPages: 0 }),
}))

// 用户已确认白底布局及泥电品牌，首页不再常驻旧封面和重复营销标题。
describe('approved homepage composition', () => {
  let app: App | undefined
  let host: HTMLDivElement | undefined
  let pinia: ReturnType<typeof createPinia> | undefined
  afterEach(() => {
    app?.unmount()
    if (pinia) disposePinia(pinia)
    app = undefined
    host?.remove()
    host = undefined
  })

  it('keeps the brand and functional entries without the old promotional footer', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: HomePage },
        { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
      ],
    })
    await router.push('/')
    host = document.createElement('div')
    document.body.append(host)
    pinia = createPinia()
    app = createApp(HomePage).use(pinia).use(router)
    app.mount(host)
    expect(host.querySelector('h1')?.textContent).toBe('泥电美食地图')
    expect(host.querySelector('.discovery-footer')?.textContent).toContain('校园美食评价社区')
    expect(host.querySelector('.discovery-footer img')).toBeNull()
    expect(host.querySelector('.filter-toolbar .random-banner')?.textContent).toContain(
      '帮我选一家',
    )
    expect(host.querySelector('input[type="search"]')?.getAttribute('placeholder')).toBe(
      '搜索餐厅、食堂或窗口…',
    )
    expect(host.textContent).not.toContain('看点评')
  })
})
