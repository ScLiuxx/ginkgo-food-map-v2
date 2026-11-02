import { createApp, nextTick } from 'vue'
import type { App } from 'vue'
import { createPinia, disposePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchRestaurantCategories, fetchRestaurants } from '../api/restaurantApi'
import { categoryFixtures, pageFixture } from '../api/restaurant.test-data'
import { useRestaurantCatalog } from '../model/catalog.store'
import RestaurantCatalog from './RestaurantCatalog.vue'

vi.mock('../api/restaurantApi', () => ({
  fetchRestaurantCategories: vi.fn(),
  fetchRestaurants: vi.fn(),
}))

/** 验证页面使用服务端总数和当前页，网络错误不能回退到旧预览快照。 */
describe('restaurant catalog API integration', () => {
  let app: App
  let host: HTMLDivElement
  let pinia: ReturnType<typeof createPinia>
  const router = createRouter({
    history: createMemoryHistory(),
    routes: ['/', '/restaurants/:id', '/scoring-guide'].map((path) => ({
      path,
      component: { template: '<div />' },
    })),
  })
  const settle = async () => {
    for (let i = 0; i < 8; i++) await nextTick()
  }
  const dialogMethods = Object.fromEntries(
    ['showModal', 'close'].map((name) => [
      name,
      Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, name),
    ]),
  )
  beforeEach(async () => {
    vi.resetAllMocks()
    for (const name of ['showModal', 'close']) {
      Object.defineProperty(HTMLDialogElement.prototype, name, {
        configurable: true,
        value: function (this: HTMLDialogElement) {
          this.toggleAttribute('open', name === 'showModal')
        },
      })
    }
    vi.mocked(fetchRestaurantCategories).mockResolvedValue(categoryFixtures)
    vi.mocked(fetchRestaurants).mockResolvedValue(pageFixture())
    pinia = createPinia()
    await router.push('/')
    host = document.createElement('div')
    document.body.append(host)
    app = createApp(RestaurantCatalog).use(pinia).use(router)
    app.mount(host)
    await settle()
  })
  afterEach(() => {
    app.unmount()
    disposePinia(pinia)
    host.remove()
    vi.restoreAllMocks()
    for (const [name, descriptor] of Object.entries(dialogMethods)) {
      if (descriptor) Object.defineProperty(HTMLDialogElement.prototype, name, descriptor)
      else Reflect.deleteProperty(HTMLDialogElement.prototype, name)
    }
  })

  it('shows server records and total without internal labels and preserves numeric links', () => {
    expect(host.querySelector('[role="note"]')?.textContent).toContain('请出发前核实')
    expect(host.textContent).not.toContain('联调模拟')
    expect(host.querySelectorAll('.restaurant-card')).toHaveLength(8)
    expect(host.querySelector('.results-toolbar')?.textContent).toContain('25')
    expect(host.querySelector('.restaurant-card')?.getAttribute('href')).toBe('/restaurants/1')
    expect(host.textContent).not.toContain('银桦餐厅')
  })

  /** 分类只有导航一个入口，翻页后切分类仍保留排序及停业语义，每次只请求一次。 */
  it('changes category without resetting independent filters and resets pagination once', async () => {
    const store = useRestaurantCatalog(pinia)
    store.filters.sort = 'value'
    store.filters.hideClosed = true
    await settle()
    store.goToPage(2)
    await settle()
    expect(store.page).toBe(2)
    const calls = vi.mocked(fetchRestaurants).mock.calls.length
    const category = [...host.querySelectorAll<HTMLButtonElement>('.quick-categories button')].find(
      (button) => button.textContent?.trim() === '火锅',
    )!
    category.click()
    await settle()
    expect(category.getAttribute('aria-pressed')).toBe('true')
    expect(fetchRestaurants).toHaveBeenCalledTimes(calls + 1)
    expect(fetchRestaurants).toHaveBeenLastCalledWith(
      {
        keyword: '',
        category: 'HOT_POT',
        sort: 'value',
        hideClosed: true,
        maxPrice: null,
        maxDistanceMeters: null,
      },
      1,
      8,
      expect.any(AbortSignal),
    )
    expect(host.querySelector('.selected-filters, .filter-trigger')).toBeNull()
    expect(document.querySelector('.catalog-filter-dialog')).toBeNull()
  })

  /** 所有既有排序都必须保留，选择后直接生效且不改变分类、搜索词或停业条件。 */
  it.each(['score', 'distance', 'price', 'reviews', 'taste', 'value', 'ambience', 'service'])(
    'applies %s from one grouped selector without losing other conditions',
    async (sort) => {
      const store = useRestaurantCatalog(pinia)
      Object.assign(store.filters, { category: 'HOT_POT', hideClosed: true, sort: 'price' })
      await settle()
      store.goToPage(3)
      await settle()
      const calls = vi.mocked(fetchRestaurants).mock.calls.length
      const select = host.querySelector<HTMLSelectElement>('[aria-label="排序方式"]')!
      expect(select.querySelectorAll('optgroup')).toHaveLength(2)
      expect(select.options).toHaveLength(8)
      select.value = sort
      select.dispatchEvent(new Event('change', { bubbles: true }))
      await settle()
      expect(fetchRestaurants).toHaveBeenCalledTimes(calls + Number(sort !== 'price'))
      expect(store.filters).toEqual({
        keyword: '',
        category: 'HOT_POT',
        sort,
        hideClosed: true,
        maxPrice: null,
        maxDistanceMeters: null,
      })
      expect(select.selectedOptions[0]?.value).toBe(sort)
      expect(store.page).toBe(sort === 'price' ? 3 : 1)
      expect(fetchRestaurants).toHaveBeenLastCalledWith(
        expect.objectContaining({ category: 'HOT_POT', sort, hideClosed: true }),
        sort === 'price' ? 3 : 1,
        8,
        expect.any(AbortSignal),
      )
    },
  )

  it('toggles the closed marker immediately and preserves category and sort in both directions', async () => {
    const store = useRestaurantCatalog(pinia)
    Object.assign(store.filters, { category: 'HOT_POT', sort: 'taste' })
    await settle()
    const toggle = host.querySelector<HTMLButtonElement>('.hide-closed')!
    for (const hideClosed of [true, false]) {
      store.goToPage(2)
      await settle()
      const calls = vi.mocked(fetchRestaurants).mock.calls.length
      toggle.click()
      await settle()
      expect(toggle.getAttribute('aria-pressed')).toBe(String(hideClosed))
      expect(fetchRestaurants).toHaveBeenCalledTimes(calls + 1)
      expect(fetchRestaurants).toHaveBeenLastCalledWith(
        {
          keyword: '',
          category: 'HOT_POT',
          sort: 'taste',
          hideClosed,
          maxPrice: null,
          maxDistanceMeters: null,
        },
        1,
        8,
        expect.any(AbortSignal),
      )
    }
    expect(toggle.textContent).toContain('隐藏停业餐厅')
    expect(host.textContent).not.toContain('只看营业中')
  })

  it('does not show an empty reset action when the unfiltered catalog is empty', async () => {
    vi.mocked(fetchRestaurants).mockResolvedValue(
      pageFixture({ total: 0, totalPages: 0, records: [] }),
    )
    await useRestaurantCatalog(pinia).load()
    await settle()
    expect(host.querySelector('.directory-empty .primary-action')).toBeNull()
  })

  it('stages type and dining choices, cancels drafts, applies together and removes one condition independently', async () => {
    const store = useRestaurantCatalog(pinia)
    store.filters.category = 'HOT_POT'
    await settle()
    const trigger = host.querySelector<HTMLButtonElement>('.more-filters')!
    trigger.click()
    await settle()
    const dialog = document.querySelector<HTMLDialogElement>('.advanced-filter-dialog')!
    const choose = (group: string, label: string) =>
      [...dialog.querySelectorAll<HTMLButtonElement>(`[data-group="${group}"] button`)]
        .find((button) => button.textContent?.trim() === label)!
        .click()
    choose('venue', '食堂')
    choose('dining', '支持外卖')
    dialog.dispatchEvent(new Event('cancel', { cancelable: true }))
    await settle()
    expect(store.filters.restaurantType).toBeUndefined()
    expect(store.filters.diningMode).toBeUndefined()
    trigger.click()
    await settle()
    choose('venue', '餐厅')
    choose('dining', '支持堂食')
    dialog.querySelector<HTMLButtonElement>('.apply-advanced')!.click()
    await settle()
    expect(store.filters).toMatchObject({
      category: 'HOT_POT',
      restaurantType: 'REGULAR',
      diningMode: 'dineIn',
    })
    expect(trigger.querySelector('.filter-count')?.textContent).toBe('2')
    host.querySelector<HTMLButtonElement>('[aria-label="移除餐厅类型筛选"]')!.click()
    await settle()
    expect(store.filters).toMatchObject({
      category: 'HOT_POT',
      restaurantType: '',
      diningMode: 'dineIn',
    })
    expect(trigger.querySelector('.filter-count')?.textContent).toBe('1')
    store.resetFilters()
    await settle()
    expect(store.filters).toMatchObject({ category: '', restaurantType: '', diningMode: '' })
    expect(host.querySelector('.advanced-tags')).toBeNull()
  })

  /** 模拟dialog只为验证状态，真实模态焦点、Escape和小屏滚动另由浏览器验收。 */
  it('applies an isolated advanced draft once and preserves all main filters', async () => {
    const store = useRestaurantCatalog(pinia)
    Object.assign(store.filters, { category: 'HOT_POT', sort: 'value', hideClosed: true })
    await settle()
    store.goToPage(2)
    await settle()
    const calls = vi.mocked(fetchRestaurants).mock.calls.length
    const trigger = host.querySelector<HTMLButtonElement>('.more-filters')!
    trigger.click()
    await settle()
    const dialog = document.querySelector<HTMLDialogElement>('.advanced-filter-dialog')!
    const choose = (label: string) =>
      [...dialog.querySelectorAll<HTMLButtonElement>('.advanced-choices button')]
        .find((b) => b.textContent?.trim() === label)!
        .click()
    choose('30 元以下')
    choose('1 公里以内')
    await settle()
    expect(fetchRestaurants).toHaveBeenCalledTimes(calls)
    expect(store.filters.maxPrice).toBeNull()
    dialog.querySelector<HTMLButtonElement>('.apply-advanced')!.click()
    await settle()
    expect(fetchRestaurants).toHaveBeenCalledTimes(calls + 1)
    expect(fetchRestaurants).toHaveBeenLastCalledWith(
      {
        keyword: '',
        category: 'HOT_POT',
        sort: 'value',
        hideClosed: true,
        maxPrice: 30,
        maxDistanceMeters: 1000,
      },
      1,
      8,
      expect.any(AbortSignal),
    )
    expect(trigger.querySelector('.filter-count')?.textContent).toBe('2')
    expect(host.querySelectorAll('.advanced-tags button')).toHaveLength(2)
    trigger.click()
    await settle()
    expect(
      [...dialog.querySelectorAll('[aria-pressed="true"]')].map((b) => b.textContent?.trim()),
    ).toEqual(['30 元以下', '1 公里以内', '全部', '不限'])
    dialog.querySelector<HTMLButtonElement>('.reset-advanced')!.click()
    dialog.dispatchEvent(new Event('cancel', { cancelable: true }))
    await settle()
    expect(store.filters.maxPrice).toBe(30)
    expect(fetchRestaurants).toHaveBeenCalledTimes(calls + 1)
    expect(document.documentElement.style.overflow).toBe('')
    trigger.click()
    await settle()
    expect(dialog.querySelector('[aria-pressed="true"]')?.textContent).toContain('30 元以下')
    dialog.querySelector<HTMLButtonElement>('.reset-advanced')!.click()
    dialog.querySelector<HTMLButtonElement>('.apply-advanced')!.click()
    await settle()
    expect(fetchRestaurants).toHaveBeenCalledTimes(calls + 2)
    expect(store.filters).toMatchObject({
      category: 'HOT_POT',
      sort: 'value',
      hideClosed: true,
      maxPrice: null,
      maxDistanceMeters: null,
    })
    expect(host.querySelector('.advanced-tags')).toBeNull()
    expect(trigger.querySelector('.filter-count')).toBeNull()
  })

  it('removes one advanced condition without resetting another and clears both through the empty state', async () => {
    const store = useRestaurantCatalog(pinia)
    Object.assign(store.filters, { maxPrice: 50, maxDistanceMeters: 2000, category: 'HOT_POT' })
    await settle()
    const calls = vi.mocked(fetchRestaurants).mock.calls.length
    host.querySelector<HTMLButtonElement>('[aria-label="移除人均50元以下筛选"]')!.click()
    await settle()
    expect(fetchRestaurants).toHaveBeenCalledTimes(calls + 1)
    expect(store.filters).toMatchObject({
      maxPrice: null,
      maxDistanceMeters: 2000,
      category: 'HOT_POT',
    })
    expect(host.querySelector('.filter-count')?.textContent).toBe('1')
    vi.mocked(fetchRestaurants).mockResolvedValue(
      pageFixture({ total: 0, totalPages: 0, records: [] }),
    )
    await store.load()
    await settle()
    host.querySelector<HTMLButtonElement>('.directory-empty .primary-action')!.click()
    await settle()
    expect(store.filters).toMatchObject({ maxPrice: null, maxDistanceMeters: null, category: '' })
    expect(host.querySelector('.advanced-tags')).toBeNull()
  })

  it('keeps focus behavior when requesting the next server page', async () => {
    const next = [...host.querySelectorAll<HTMLButtonElement>('.catalog-pagination button')].find(
      (button) => button.textContent?.includes('下一页'),
    )!
    next.click()
    await settle()
    expect(fetchRestaurants).toHaveBeenLastCalledWith(
      expect.anything(),
      2,
      8,
      expect.any(AbortSignal),
    )
    expect(document.activeElement).toBe(host.querySelector('.results-toolbar'))
  })

  it('shows server emptiness without inventing low scores', async () => {
    vi.mocked(fetchRestaurants).mockResolvedValue(
      pageFixture({ total: 0, totalPages: 0, records: [] }),
    )
    useRestaurantCatalog(pinia).filters.category = 'HOT_POT'
    await settle()
    expect(host.querySelector('.directory-empty')?.textContent).toContain('没有找到符合条件')
    expect(host.querySelectorAll('.restaurant-card')).toHaveLength(0)
  })

  it('shows failure and retries instead of displaying preview restaurants', async () => {
    vi.mocked(fetchRestaurants).mockRejectedValueOnce(new Error('联调网络故障'))
    await useRestaurantCatalog(pinia).load()
    await settle()
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('联调网络故障')
    expect(host.querySelectorAll('.restaurant-card')).toHaveLength(0)
    host.querySelector<HTMLButtonElement>('[role="alert"] button')!.click()
    await settle()
    expect(host.querySelector('[role="alert"]')).toBeNull()
    expect(host.querySelectorAll('.restaurant-card')).toHaveLength(8)
  })
})
