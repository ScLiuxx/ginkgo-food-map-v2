import { captureLinkTarget } from '@/shared/test/captureLinkTarget'
import { captureResourceTarget } from '@/shared/test/captureResourceTarget'
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
      await select(group, value)
    await settle()
  }
  /** 通过真实的展开入口选值，避免测试直接点击已收起、用户无法触达的选项。 */
  async function select(group: string, value: string) {
    const toggle = host.querySelector<HTMLButtonElement>(`[data-group="${group}"] .field-toggle`)!
    if (toggle.getAttribute('aria-expanded') !== 'true') {
      toggle.click()
      await settle()
    }
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
    expect(
      await captureLinkTarget(app, host.querySelector<HTMLAnchorElement>('.actions a')),
    ).toContain('from=picker')
    expect(catalog.filters.keyword).toBe('火锅')
  })
  it('评分不预选，简短结果摘要不丢失完整条件，修改与取消保留原门槛', async () => {
    await open()
    await fill('')
    expect(host.querySelector('[type="submit"]')?.textContent).toContain('还需选择 1 项')
    expect(host.querySelector<HTMLButtonElement>('[type="submit"]')!.disabled).toBe(true)
    await select('minRating', '3.5')
    await settle()
    expect(host.querySelector<HTMLButtonElement>('[type="submit"]')!.disabled).toBe(false)
    vi.mocked(fetchPickerCandidates).mockResolvedValue({
      records: [restaurantFixture],
      matchedCount: 1,
      remainingCount: 0,
    })
    await draw()
    expect(vi.mocked(fetchPickerCandidates).mock.calls[0]![0].minRating).toBe('3.5')
    expect(host.querySelector('.summary-tags')?.textContent).toContain('清水河附近')
    expect(host.querySelectorAll('.summary-tags span')).toHaveLength(2)
    host.querySelector<HTMLButtonElement>('.condition-summary button')!.click()
    await settle()
    expect(
      host
        .querySelector('[data-group="minRating"] [data-value="3.5"]')
        ?.getAttribute('aria-pressed'),
    ).toBe('true')
    await select('minRating', '4')
    host.querySelector<HTMLButtonElement>('.cancel-edit')!.click()
    await settle()
    host.querySelector<HTMLButtonElement>('.condition-summary button')!.click()
    await settle()
    expect(host.querySelector('#picker-minRating')?.textContent).toContain('3.5分及以上')
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
    await select('range', 'USER')
    await settle()
    expect(host.querySelector<HTMLButtonElement>('[type="submit"]')!.disabled).toBe(true)
  })
  it('我附近成功定位才发送坐标，切回校区后忽略迟到定位', async () => {
    await open()
    await fill()
    await select('range', 'USER')
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
    await select('range', 'SHAHE')
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
  it('六行初始收起，一次只展开一组；选后更新摘要并恢复行焦点，重开保留已选值', async () => {
    await open()
    const toggles = [...host.querySelectorAll<HTMLButtonElement>('.field-toggle')]
    expect(toggles).toHaveLength(6)
    expect(toggles.every((button) => button.getAttribute('aria-expanded') === 'false')).toBe(true)
    expect(host.querySelector('[type="submit"]')?.textContent).toContain('还需选择 6 项')
    toggles[0]!.click()
    await settle()
    expect(host.querySelector<HTMLElement>('#picker-options-range')?.style.display).not.toBe('none')
    toggles[1]!.click()
    await settle()
    expect(toggles[0]!.getAttribute('aria-expanded')).toBe('false')
    expect(host.querySelector<HTMLElement>('#picker-options-range')?.style.display).toBe('none')
    await select('distanceMeters', '3000')
    expect(toggles[1]!.textContent).toContain('3公里内')
    expect(toggles[1]!.getAttribute('aria-expanded')).toBe('false')
    expect(document.activeElement).toBe(toggles[1])
    toggles[1]!.click()
    await settle()
    expect(host.querySelector('[data-value="3000"]')?.getAttribute('aria-pressed')).toBe('true')
    toggles[1]!.click()
    await settle()
    expect(toggles[1]!.getAttribute('aria-expanded')).toBe('false')
    expect(fetchPickerCandidates).not.toHaveBeenCalled()
  })
  it('类别失败可在展开行重试，不清空其他条件；不限始终是主动选择', async () => {
    vi.mocked(fetchRestaurantCategories).mockRejectedValueOnce(new Error('类别读取失败'))
    await open()
    await select('budget', 'ANY')
    host.querySelector<HTMLButtonElement>('#picker-category')!.click()
    await settle()
    expect(host.querySelector('[data-group="category"] [role="alert"]')?.textContent).toContain(
      '类别读取失败',
    )
    expect(useRestaurantPicker(pinia).draft.category).toBe('')
    host.querySelector<HTMLButtonElement>('[data-group="category"] .text-button')!.click()
    await settle()
    await select('category', 'FAST_FOOD')
    expect(host.querySelector('#picker-category')?.textContent).toContain('小吃快餐')
    expect(host.querySelector('#picker-budget')?.textContent).toContain('不限预算')
    expect(useRestaurantPicker(pinia).completedCount).toBe(2)
    expect(fetchPickerCandidates).not.toHaveBeenCalled()
  })
  it('我附近收起后仍显示定位进度及失败重试，成功前不能提交', async () => {
    await open()
    await fill()
    vi.mocked(useUserLocation().locate).mockImplementation(async () => {
      useUserLocation().locating = true
    })
    await select('range', 'USER')
    expect(host.querySelector('#picker-range')?.getAttribute('aria-expanded')).toBe('false')
    expect(host.querySelector('.location-feedback')?.textContent).toContain('正在获取位置')
    expect(host.querySelector('[type="submit"]')?.textContent).toContain('正在定位')
    expect(host.querySelector<HTMLButtonElement>('[type="submit"]')!.disabled).toBe(true)
    useUserLocation().locating = false
    await settle()
    expect(host.querySelector('.location-feedback')?.textContent).toContain('定位尚未成功')
    const retry = host.querySelector<HTMLButtonElement>('.location-feedback button')!
    retry.click()
    await settle()
    expect(useUserLocation().locate).toHaveBeenCalledTimes(2)
    useUserLocation().location = { center: [104.1, 30.6], accuracy: 10, timestamp: Date.now() }
    useUserLocation().locating = false
    await settle()
    expect(host.querySelector<HTMLButtonElement>('[type="submit"]')!.disabled).toBe(false)
    expect(host.querySelector('[type="submit"]')?.textContent).toContain('帮我选一家')
  })
  it('无照片不占位，图片失败后收起，换店重新展示新封面；校区距离如实标注', async () => {
    await open()
    await fill()
    const first = {
      ...restaurantFixture,
      id: '501',
      coverPhoto: { id: '11', url: '/first.jpg' },
      distanceKm: 2.2,
    }
    const second = {
      ...restaurantFixture,
      id: '502',
      coverPhoto: { id: '12', url: '/second.jpg' },
      distanceKm: null,
    }
    vi.mocked(fetchPickerCandidates)
      .mockResolvedValueOnce({ records: [first, second], matchedCount: 2, remainingCount: 0 })
      .mockResolvedValueOnce({ records: [second], matchedCount: 2, remainingCount: 0 })
    await draw()
    expect(host.querySelector('.distance')?.textContent).toBe('距清水河 2.2 公里')
    host.querySelector<HTMLImageElement>('.restaurant-photo img')!.dispatchEvent(new Event('error'))
    await settle()
    expect(host.querySelector('.restaurant-photo')).toBeNull()
    expect(host.textContent).not.toContain('暂无商户照片')
    host.querySelector<HTMLButtonElement>('.result-footer .next')!.click()
    await settle()
    expect(host.querySelector<HTMLImageElement>('.restaurant-photo img')?.getAttribute('src')).toBe(
      '/second.jpg',
    )
    expect(host.querySelector('.distance')?.textContent).toBe('距离待补')
    expect(host.querySelector('.price')?.textContent).toBe('人均待补')
    expect(host.querySelector('.result-footer .next')?.textContent).toContain('重新抽一轮')
    expect(host.querySelector('.result-note')).toBeNull()
  })
  it('文字详情和导航仍遵守资料复核状态，失效或读取失败时不能前往', async () => {
    await open()
    await fill()
    const current = {
      ...restaurantFixture,
      id: '501',
      latitude: 30.6,
      longitude: 104.1,
      needsLocation: false,
      score: 0,
    }
    vi.mocked(fetchPickerCandidates).mockResolvedValueOnce({
      records: [current],
      matchedCount: 1,
      remainingCount: 0,
    })
    await draw()
    expect(host.querySelector('.restaurant-photo')).toBeNull()
    expect(host.querySelector('.rating')?.textContent).toContain('0.0分')
    expect(captureResourceTarget(host.querySelector('.navigation-link'))).toContain(
      'position=104.1%2C30.6',
    )
    const picker = useRestaurantPicker(pinia)
    vi.mocked(fetchPickerCandidates).mockRejectedValueOnce(new Error('复核失败'))
    await picker.visit(0)
    await settle()
    expect(host.querySelector('.actions a')).toBeNull()
    expect(
      [...host.querySelectorAll<HTMLButtonElement>('.actions button')].every((b) => b.disabled),
    ).toBe(true)
    vi.mocked(fetchPickerCandidates).mockResolvedValueOnce({
      records: [],
      matchedCount: 0,
      remainingCount: 0,
    })
    await picker.retry()
    await settle()
    expect(host.textContent).toContain('这家店已不符合本轮条件')
    expect(host.querySelector('.actions a')).toBeNull()
    expect(host.querySelector<HTMLButtonElement>('.result-footer .next')?.disabled).toBe(false)
  })
})
