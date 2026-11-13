import { createApp, nextTick } from 'vue'
import type { App } from 'vue'
import { createPinia, disposePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest'
import RandomRestaurant from './RandomRestaurant.vue'
import { fetchRestaurantCategories } from '../api/restaurantApi'
import { fetchPickerCandidates } from '../api/restaurantPickerApi'
import { restaurantFixture } from '../api/restaurant.test-data'
import { useRestaurantCatalog } from '../model/catalog.store'
import { useRestaurantPicker } from '../model/picker.store'
import { useUserLocation } from '@/modules/map/model/userLocation.store'

vi.mock('../api/restaurantApi', () => ({
  fetchRestaurantCategories: vi.fn(),
  fetchRestaurants: vi.fn(),
}))
vi.mock('../api/restaurantPickerApi', () => ({ fetchPickerCandidates: vi.fn() }))
vi.mock('@/modules/map/model/userLocation.store', async () => {
  const { reactive } = await import('vue')
  const state = reactive({ location: null, preparing: false, locating: false, locate: vi.fn() })
  return { useUserLocation: () => state }
})

describe('选店弹窗', () => {
  let app: App, host: HTMLDivElement, pinia: ReturnType<typeof createPinia>
  const router = createRouter({
    history: createMemoryHistory(),
    routes: ['/', '/restaurants/:id'].map((path) => ({ path, component: { template: '<div />' } })),
  })
  const settle = async () => {
    for (let i = 0; i < 14; i++) await nextTick()
  }
  beforeEach(async () => {
    vi.resetAllMocks()
    Object.assign(useUserLocation(), { location: null, preparing: false, locating: false })
    vi.mocked(fetchRestaurantCategories).mockResolvedValue([
      { code: 'FAST_FOOD', name: '小吃快餐', sortOrder: 1, enabled: true },
    ])
    host = document.createElement('div')
    document.body.append(host)
    pinia = createPinia()
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
      configurable: true,
      value: function (this: HTMLDialogElement) {
        this.open = true
      },
    })
    Object.defineProperty(HTMLDialogElement.prototype, 'close', {
      configurable: true,
      value: function (this: HTMLDialogElement) {
        this.open = false
      },
    })
    await router.push('/')
    app = createApp(RandomRestaurant).use(pinia).use(router)
    app.mount(host)
    await settle()
  })
  afterEach(() => {
    app.unmount()
    disposePinia(pinia)
    host.remove()
    vi.restoreAllMocks()
  })
  async function open() {
    host.querySelector<HTMLButtonElement>('.random-banner button')!.click()
    await settle()
  }
  async function fill(minRating = 'ANY') {
    for (const [group, value] of Object.entries({
      range: 'QINGSHUIHE',
      distanceMeters: '3000',
      budget: '30',
      ...(minRating ? { minRating } : {}),
      category: 'ANY',
      diningMode: 'ANY',
    }))
      host
        .querySelector<HTMLButtonElement>(`[data-group="${group}"] [data-value="${value}"]`)!
        .click()
    await settle()
  }
  async function draw() {
    host
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await settle()
  }
  it('打开不抽取、不预选，与首页条件独立；选齐才能抽', async () => {
    const catalog = useRestaurantCatalog(pinia)
    catalog.filters.keyword = '火锅'
    await open()
    expect(host.querySelectorAll('.selected')).toHaveLength(0)
    expect(host.querySelector<HTMLButtonElement>('[type="submit"]')!.disabled).toBe(true)
    expect(fetchPickerCandidates).not.toHaveBeenCalled()
    await fill()
    vi.mocked(fetchPickerCandidates).mockResolvedValue({
      records: [{ ...restaurantFixture, name: '符合条件的店' }],
      matchedCount: 1,
      remainingCount: 0,
    })
    await draw()
    expect(fetchPickerCandidates).toHaveBeenCalledWith(
      {
        range: 'QINGSHUIHE',
        distanceMeters: 3000,
        budget: '30',
        minRating: 'ANY',
        category: 'ANY',
        diningMode: 'ANY',
      },
      [],
      [],
      expect.any(AbortSignal),
    )
    expect(host.querySelector('.restaurant-title')?.textContent).toContain('符合条件的店')
    expect(host.textContent).toContain('只有这一家')
    expect(host.querySelector<HTMLAnchorElement>('.actions a')?.getAttribute('href')).toContain(
      'from=picker',
    )
    expect(catalog.filters.keyword).toBe('火锅')
  })
  it('评分不预选，选齐六项后提交门槛并在摘要和修改条件中保留', async () => {
    await open()
    await fill('')
    expect(host.textContent).toContain('已选 5 / 6 项条件')
    expect(host.querySelector<HTMLButtonElement>('[type="submit"]')!.disabled).toBe(true)
    host.querySelector<HTMLButtonElement>('[data-group="minRating"] [data-value="3.5"]')!.click()
    await settle()
    expect(host.querySelector<HTMLButtonElement>('[type="submit"]')!.disabled).toBe(false)
    vi.mocked(fetchPickerCandidates).mockResolvedValue({
      records: [restaurantFixture],
      matchedCount: 1,
      remainingCount: 0,
    })
    await draw()
    expect(vi.mocked(fetchPickerCandidates).mock.calls[0]![0].minRating).toBe('3.5')
    expect(host.querySelector('.summary-tags')?.textContent).toContain('3.5分及以上')
    host.querySelector<HTMLButtonElement>('.condition-summary button')!.click()
    await settle()
    expect(
      host
        .querySelector('[data-group="minRating"] [data-value="3.5"]')
        ?.getAttribute('aria-pressed'),
    ).toBe('true')
    host.querySelector<HTMLButtonElement>('[data-group="minRating"] [data-value="4"]')!.click()
    host.querySelector<HTMLButtonElement>('.cancel-edit')!.click()
    await settle()
    expect(host.querySelector('.summary-tags')?.textContent).toContain('3.5分及以上')
  })
  it('零候选不会放宽；改条件保留值，关闭再进入清空', async () => {
    await open()
    await fill()
    vi.mocked(fetchPickerCandidates).mockResolvedValue({
      records: [],
      matchedCount: 0,
      remainingCount: 0,
    })
    await draw()
    expect(host.querySelector('.empty-body')?.textContent).toContain('没有符合条件')
    expect(fetchPickerCandidates).toHaveBeenCalledTimes(1)
    host.querySelector<HTMLButtonElement>('.empty-body button')!.click()
    await settle()
    expect(host.querySelectorAll('.selected')).toHaveLength(6)
    host.querySelector<HTMLButtonElement>('[aria-label="关闭选店"]')!.click()
    await settle()
    await open()
    expect(host.querySelectorAll('.selected')).toHaveLength(0)
  })
  it('故障提供重试；我附近尚未定位不能抽取', async () => {
    await open()
    await fill()
    vi.mocked(fetchPickerCandidates).mockRejectedValueOnce(new Error('网络暂不可用'))
    await draw()
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('网络暂不可用')
    expect(host.querySelector('.empty-body')).toBeNull()
    host.querySelector<HTMLButtonElement>('.condition-summary button')!.click()
    await settle()
    host.querySelector<HTMLButtonElement>('[data-value="USER"]')!.click()
    await settle()
    expect(host.querySelector<HTMLButtonElement>('[type="submit"]')!.disabled).toBe(true)
  })
  it('我附近成功定位才发送坐标，切回校区后忽略迟到定位', async () => {
    await open()
    await fill()
    host.querySelector<HTMLButtonElement>('[data-value="USER"]')!.click()
    await settle()
    expect(host.querySelector<HTMLButtonElement>('[type="submit"]')!.disabled).toBe(true)
    useUserLocation().location = { center: [104.1, 30.6], accuracy: 10, timestamp: Date.now() }
    await settle()
    expect(host.querySelector<HTMLButtonElement>('[type="submit"]')!.disabled).toBe(false)
    vi.mocked(fetchPickerCandidates).mockResolvedValue({
      records: [],
      matchedCount: 0,
      remainingCount: 0,
    })
    await draw()
    expect(vi.mocked(fetchPickerCandidates).mock.calls[0]![0]).toMatchObject({
      range: 'USER',
      longitude: 104.1,
      latitude: 30.6,
    })
    host.querySelector<HTMLButtonElement>('.empty-body button')!.click()
    await settle()
    host.querySelector<HTMLButtonElement>('[data-value="SHAHE"]')!.click()
    useUserLocation().location = { center: [105, 31], accuracy: 10, timestamp: Date.now() }
    await settle()
    expect(host.querySelector('[data-value="SHAHE"]')?.getAttribute('aria-pressed')).toBe('true')
    await draw()
    expect(vi.mocked(fetchPickerCandidates).mock.calls[1]![0]).not.toHaveProperty('latitude')
  })
  it('普通详情点击暂挂本轮，修饰键保留原弹窗', async () => {
    await open()
    await fill()
    vi.mocked(fetchPickerCandidates).mockResolvedValue({
      records: [restaurantFixture],
      matchedCount: 1,
      remainingCount: 0,
    })
    await draw()
    const link = host.querySelector<HTMLAnchorElement>('.actions a')!
    link.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, ctrlKey: true }))
    expect(host.querySelector('dialog')!.open).toBe(true)
    expect(useRestaurantPicker(pinia).pendingReturn).toBe(false)
    link.click()
    await settle()
    expect(useRestaurantPicker(pinia).pendingReturn).toBe(true)
    expect(useRestaurantPicker(pinia).history).toHaveLength(1)
    expect(host.querySelector('dialog')!.open).toBe(false)
  })
})
