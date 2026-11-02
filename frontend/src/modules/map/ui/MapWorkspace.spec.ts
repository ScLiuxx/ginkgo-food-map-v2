import { createApp, nextTick, type App } from 'vue'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RestaurantMap from './MapWorkspace.vue'
import { fetchRestaurants } from '@/modules/restaurant/api/restaurantApi'

vi.mock('@/modules/restaurant/api/restaurantApi', () => ({
  fetchRestaurants: vi.fn(),
  fetchRestaurantCategories: vi.fn(),
}))

/** 界面预览独立验证，不把示例和SVG位置传给业务接口或高德。 */
describe('地图界面预览隔离与选店', () => {
  let app: App
  let host: HTMLDivElement
  afterEach(() => {
    app?.unmount()
    host?.remove()
    vi.clearAllMocks()
  })
  async function mount() {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
    })
    await router.push('/previews/map')
    host = globalThis.document.createElement('div')
    globalThis.document.body.append(host)
    app = createApp(RestaurantMap, { preview: true }).use(createPinia()).use(router)
    app.mount(host)
    await nextTick()
  }
  it('示例餐厅不发真实请求、不生成真实详情和导航链接', async () => {
    await mount()
    expect(fetchRestaurants).not.toHaveBeenCalled()
    expect(host.textContent).toContain('非真实地图')
    expect(host.querySelectorAll('.map-card-list .map-restaurant')).toHaveLength(3)
    expect(host.querySelector('.map-selection a')).toBeNull()
    expect(host.querySelector<HTMLButtonElement>('.map-selection .map-navigate')?.disabled).toBe(
      true,
    )
  })
  it('点击点位同步选店，分类筛选清理失效选择', async () => {
    await mount()
    host.querySelectorAll<HTMLButtonElement>('.score-pin')[1]!.click()
    await nextTick()
    expect(host.querySelector('.map-selection')?.textContent).toContain('示例火锅店')
    const category = [...host.querySelectorAll<HTMLButtonElement>('.map-categories button')].find(
      (b) => b.textContent === '川菜',
    )!
    category.click()
    await nextTick()
    expect(host.querySelectorAll('.map-card-list .map-restaurant')).toHaveLength(1)
    expect(host.querySelector('.map-selection')?.textContent).toContain('示例川菜馆')
    expect(fetchRestaurants).not.toHaveBeenCalled()
  })
})
