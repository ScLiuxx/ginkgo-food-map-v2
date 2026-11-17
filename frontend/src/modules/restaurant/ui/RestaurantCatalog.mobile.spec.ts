import { createApp, nextTick, type App } from 'vue'
import { createPinia, disposePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchRestaurantCategories, fetchRestaurants } from '../api/restaurantApi'
import { categoryFixtures, pageFixture, restaurantFixture } from '../api/restaurant.test-data'
import { useRestaurantCatalog } from '../model/catalog.store'
import RestaurantCatalog from './RestaurantCatalog.vue'

vi.mock('../api/restaurantApi', () => ({
  fetchRestaurantCategories: vi.fn(),
  fetchRestaurants: vi.fn(),
}))
// 本组只覆盖目录加载和生命周期，筛选控件本身由原有目录集成测试覆盖。
vi.mock('./RestaurantFilters.vue', () => ({ default: { template: '<div />' } }))

describe('mobile catalog continuous browsing', () => {
  let app: App
  let host: HTMLDivElement
  let pinia: ReturnType<typeof createPinia>
  let intersect: IntersectionObserverCallback
  let mediaChange: () => void
  let mobile: boolean
  const disconnect = vi.fn()
  const observe = vi.fn()
  const removeListener = vi.fn()
  const settle = async () => {
    for (let index = 0; index < 12; index++) await nextTick()
  }
  function mount(): void {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
    })
    app = createApp(RestaurantCatalog).use(pinia).use(router)
    app.mount(host)
  }
  function approachEnd(): void {
    intersect([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver)
  }
  beforeEach(async () => {
    vi.resetAllMocks()
    mobile = true
    vi.stubGlobal('matchMedia', () => ({
      get matches() {
        return mobile
      },
      addEventListener: (_event: string, callback: () => void) => {
        mediaChange = callback
      },
      removeEventListener: removeListener,
    }))
    vi.stubGlobal(
      'IntersectionObserver',
      vi.fn(function (callback: IntersectionObserverCallback) {
        intersect = callback
        return { observe, disconnect }
      }),
    )
    vi.mocked(fetchRestaurantCategories).mockResolvedValue(categoryFixtures)
    vi.mocked(fetchRestaurants).mockImplementation(async (_filters, page, size) =>
      pageFixture({
        page,
        pageSize: size,
        total: 45,
        totalPages: Math.ceil(45 / size),
        records: Array.from({ length: Math.min(size, 45 - (page - 1) * size) }, (_, index) => ({
          ...restaurantFixture,
          id: String((page - 1) * size + index + 1),
        })),
      }),
    )
    pinia = createPinia()
    host = document.createElement('div')
    document.body.append(host)
    mount()
    await settle()
  })
  afterEach(() => {
    app.unmount()
    disposePinia(pinia)
    host.remove()
    vi.unstubAllGlobals()
  })

  it('automatically appends near the bottom and offers submission only after the final batch', async () => {
    expect(host.querySelectorAll('.restaurant-card')).toHaveLength(20)
    expect(host.querySelector('.catalog-pagination')).toBeNull()
    expect(host.querySelector('.directory-add')).toBeNull()
    expect(observe).toHaveBeenCalled()
    approachEnd()
    await settle()
    expect(host.querySelectorAll('.restaurant-card')).toHaveLength(40)
    expect(host.querySelector('.directory-add')).toBeNull()
    approachEnd()
    await settle()
    expect(host.querySelectorAll('.restaurant-card')).toHaveLength(45)
    expect(host.querySelector('.catalog-load-more')).toBeNull()
    expect(host.querySelector('.directory-add .submit-restaurant-button')).not.toBeNull()
    expect(host.querySelector('.results-toolbar')?.textContent).toContain('已显示 45 家')
  })

  it('keeps the list on failure and requires explicit retry instead of an observer retry loop', async () => {
    vi.mocked(fetchRestaurants).mockRejectedValueOnce(new Error('读取下一批失败'))
    approachEnd()
    await settle()
    expect(host.querySelectorAll('.restaurant-card')).toHaveLength(20)
    expect(host.querySelector('.catalog-load-more [role="alert"]')?.textContent).toContain(
      '读取下一批失败',
    )
    const calls = vi.mocked(fetchRestaurants).mock.calls.length
    approachEnd()
    await settle()
    expect(fetchRestaurants).toHaveBeenCalledTimes(calls)
    host.querySelector<HTMLButtonElement>('.catalog-load-more button')!.click()
    await settle()
    expect(host.querySelectorAll('.restaurant-card')).toHaveLength(40)
    expect(host.querySelector('[role="alert"]')).toBeNull()
  })

  it('restores cached batches after remount, cleans up listeners and switches to desktop pagination', async () => {
    approachEnd()
    await settle()
    const calls = vi.mocked(fetchRestaurants).mock.calls.length
    app.unmount()
    expect(removeListener).toHaveBeenCalledWith('change', expect.any(Function))
    expect(disconnect).toHaveBeenCalled()
    mount()
    await settle()
    expect(host.querySelectorAll('.restaurant-card')).toHaveLength(40)
    expect(fetchRestaurants).toHaveBeenCalledTimes(calls)
    mobile = false
    mediaChange()
    await settle()
    expect(host.querySelectorAll('.restaurant-card')).toHaveLength(8)
    expect(host.querySelector('.catalog-load-more')).toBeNull()
    expect(host.querySelector('.catalog-pagination')).not.toBeNull()
  })

  it('retains a manual loading fallback when IntersectionObserver is unavailable', async () => {
    vi.stubGlobal('IntersectionObserver', undefined)
    await useRestaurantCatalog(pinia).load()
    await settle()
    host.querySelector<HTMLButtonElement>('.catalog-load-more button')!.click()
    await settle()
    expect(host.querySelectorAll('.restaurant-card')).toHaveLength(40)
  })
})
