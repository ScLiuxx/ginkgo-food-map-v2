import { createApp, nextTick, reactive, type App } from 'vue'
import { createPinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RestaurantAdvancedFilters from './RestaurantAdvancedFilters.vue'
import type { CatalogFilters } from '../model/restaurant.types'

/** 地图收进面板的分类与停业状态仍需遵守草稿规则，取消不能触发真实查询条件变化。 */
describe('地图筛选草稿', () => {
  let app: App
  let host: HTMLDivElement
  let filters: CatalogFilters
  const dialogMethods = Object.fromEntries(
    ['showModal', 'close'].map((name) => [
      name,
      Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, name),
    ]),
  )
  beforeEach(() => {
    // jsdom不实现原生dialog方法；只替换开关，真实模态与滚动在浏览器中验证。
    for (const name of ['showModal', 'close']) {
      Object.defineProperty(HTMLDialogElement.prototype, name, {
        configurable: true,
        value: function (this: HTMLDialogElement) {
          this.open = name === 'showModal'
        },
      })
    }
    filters = reactive<CatalogFilters>({
      keyword: '火锅',
      category: '',
      sort: 'reviews',
      hideClosed: false,
      maxPrice: null,
      maxDistanceMeters: null,
      longitude: 103.9312,
      latitude: 30.7544,
    })
    host = document.createElement('div')
    document.body.append(host)
    app = createApp(RestaurantAdvancedFilters, {
      filters,
      campusFilters: true,
      mapFilters: true,
      categories: [{ code: 'HOT_POT', name: '火锅串串' }],
    }).use(createPinia())
    app.mount(host)
  })
  afterEach(() => {
    app.unmount()
    host.remove()
    vi.restoreAllMocks()
    for (const [name, descriptor] of Object.entries(dialogMethods)) {
      if (descriptor) Object.defineProperty(HTMLDialogElement.prototype, name, descriptor)
      else Reflect.deleteProperty(HTMLDialogElement.prototype, name)
    }
  })
  const dialog = () => document.querySelector<HTMLDialogElement>('.advanced-filter-dialog')!
  async function open() {
    host.querySelector<HTMLButtonElement>('.more-filters')!.click()
    await nextTick()
  }
  async function edit() {
    dialog().querySelectorAll<HTMLButtonElement>('[data-group="category"] button')[1]!.click()
    const checkbox = dialog().querySelector<HTMLInputElement>('input[type="checkbox"]')!
    checkbox.checked = true
    checkbox.dispatchEvent(new Event('change', { bubbles: true }))
    dialog().querySelectorAll<HTMLButtonElement>('[data-group="price"] button')[2]!.click()
    await nextTick()
  }
  it('取消分类、预算和隐藏停业草稿时保持原条件，重新打开从原条件回显', async () => {
    await open()
    await edit()
    expect(filters).toMatchObject({ category: '', hideClosed: false, maxPrice: null })
    dialog().querySelector<HTMLButtonElement>('[aria-label="关闭更多筛选"]')!.click()
    await open()
    expect(dialog().querySelector<HTMLInputElement>('input[type="checkbox"]')!.checked).toBe(false)
    expect(
      dialog()
        .querySelector('[data-group="category"] button[aria-pressed="true"]')!
        .textContent?.trim(),
    ).toBe('全部美食')
  })
  it('确认时一次应用分类、预算和经营状态，并保留排序、关键词及参照坐标', async () => {
    await open()
    await edit()
    dialog().querySelector<HTMLButtonElement>('.apply-advanced')!.click()
    await nextTick()
    expect(filters).toMatchObject({
      category: 'HOT_POT',
      hideClosed: true,
      maxPrice: 30,
      keyword: '火锅',
      sort: 'reviews',
      longitude: 103.9312,
      latitude: 30.7544,
    })
    expect(host.querySelector('.filter-count')?.textContent?.trim()).toBe('3')
    expect(dialog().open).toBe(false)
  })
  it('点评条件取消不生效，确认、回显和重置均保留无点评的false值', async () => {
    await open()
    const choices = () =>
      dialog().querySelectorAll<HTMLButtonElement>('[data-group="reviews"] button')
    choices()[2]!.click()
    dialog().querySelector<HTMLButtonElement>('[aria-label="关闭更多筛选"]')!.click()
    await open()
    expect(choices()[0]!.getAttribute('aria-pressed')).toBe('true')
    expect(filters.hasReviews).toBeUndefined()
    choices()[2]!.click()
    dialog().querySelector<HTMLButtonElement>('.apply-advanced')!.click()
    await nextTick()
    expect(filters.hasReviews).toBe(false)
    expect(host.querySelector('.filter-count')?.textContent?.trim()).toBe('1')
    await open()
    expect(choices()[2]!.getAttribute('aria-pressed')).toBe('true')
    choices()[1]!.click()
    dialog().querySelector<HTMLButtonElement>('.apply-advanced')!.click()
    expect(filters.hasReviews).toBe(true)
    await open()
    dialog().querySelector<HTMLButtonElement>('.reset-advanced')!.click()
    dialog().querySelector<HTMLButtonElement>('.apply-advanced')!.click()
    await nextTick()
    expect(filters.hasReviews).toBeNull()
    expect(host.querySelector('.filter-count')).toBeNull()
  })
})
