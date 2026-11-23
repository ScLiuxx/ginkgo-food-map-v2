import { createPinia, disposePinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchRestaurantCategories, fetchRestaurants } from '../api/restaurantApi'
import type { RestaurantPage } from '../api/restaurantApi'
import { categoryFixtures, pageFixture, restaurantFixture } from '../api/restaurant.test-data'
import { useRestaurantCatalog } from './catalog.store'

vi.mock('../api/restaurantApi', () => ({
  fetchRestaurantCategories: vi.fn(),
  fetchRestaurants: vi.fn(),
}))

/** 网络乱序、搜索防抖和服务端分页是本次接入的边界，不能由快照筛选测试代替。 */
describe('server restaurant catalog state', () => {
  let pinia: ReturnType<typeof createPinia>
  const settle = async () => {
    for (let i = 0; i < 8; i++) await nextTick()
  }
  beforeEach(() => {
    vi.resetAllMocks()
    pinia = createPinia()
    setActivePinia(pinia)
    vi.mocked(fetchRestaurantCategories).mockResolvedValue(categoryFixtures)
    vi.mocked(fetchRestaurants).mockResolvedValue(pageFixture())
  })
  afterEach(() => {
    disposePinia(pinia)
    vi.useRealTimers()
  })

  it('preserves server ordering and counts instead of filtering or slicing the page', async () => {
    const result = pageFixture({ total: 185, totalPages: 24 })
    result.records[0]!.name = 'Z服务端第一名'
    result.records[1]!.name = 'A服务端第二名'
    vi.mocked(fetchRestaurants).mockResolvedValue(result)
    const store = useRestaurantCatalog()
    await store.load()
    expect(store.total).toBe(185)
    expect(store.visibleRestaurants).toEqual(result.records)
    expect(store.pageNumbers.length).toBeLessThanOrEqual(7)
    store.goToPage(2)
    await settle()
    expect(fetchRestaurants).toHaveBeenLastCalledWith(
      expect.anything(),
      2,
      8,
      expect.any(AbortSignal),
    )
    expect(store.visibleRestaurants).toHaveLength(8)
  })

  it('discards stale responses even when the network ignores cancellation', async () => {
    let resolveOld!: (value: RestaurantPage) => void
    vi.mocked(fetchRestaurants).mockReturnValueOnce(
      new Promise((resolve) => {
        resolveOld = resolve
      }),
    )
    const store = useRestaurantCatalog()
    const oldRequest = store.load()
    const previousSignal = vi.mocked(fetchRestaurants).mock.calls[0]![3]!
    store.filters.category = 'HOT_POT'
    await settle()
    expect(previousSignal.aborted).toBe(true)
    resolveOld(pageFixture({ total: 999, records: [] }))
    await oldRequest
    expect(store.total).toBe(25)
    expect(store.visibleRestaurants).toHaveLength(8)
  })

  it('debounces keywords, resets pagination, and sends only the latest keyword', async () => {
    vi.useFakeTimers()
    const store = useRestaurantCatalog()
    await store.load()
    store.goToPage(4)
    await settle()
    vi.mocked(fetchRestaurants).mockClear()
    store.filters.keyword = '火'
    await settle()
    store.filters.keyword = '火锅'
    await settle()
    expect(store.page).toBe(1)
    expect(fetchRestaurants).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(250)
    expect(fetchRestaurants).toHaveBeenCalledTimes(1)
    expect(fetchRestaurants).toHaveBeenCalledWith(
      expect.objectContaining({ keyword: '火锅' }),
      1,
      8,
      expect.any(AbortSignal),
    )
  })

  it('reloads the remaining last page when deletion reduces the page count', async () => {
    const store = useRestaurantCatalog()
    await store.load()
    vi.mocked(fetchRestaurants).mockResolvedValue(pageFixture({ total: 1, totalPages: 1 }))
    store.goToPage(4)
    await settle()
    expect(store.page).toBe(1)
    expect(fetchRestaurants).toHaveBeenLastCalledWith(
      expect.anything(),
      1,
      8,
      expect.any(AbortSignal),
    )
    expect(store.total).toBe(1)
  })

  it('clears failed results, retries dictionary loading, and never falls back to preview data', async () => {
    vi.mocked(fetchRestaurantCategories).mockRejectedValueOnce(new Error('分类暂不可用'))
    const store = useRestaurantCatalog()
    await store.load()
    expect(store.error).toBe('分类暂不可用')
    expect(store.visibleRestaurants).toEqual([])
    await store.load()
    expect(store.error).toBeNull()
    expect(store.categories).toEqual(categoryFixtures)
    expect(store.visibleRestaurants).toHaveLength(8)
  })

  it('cancels pending search when leaving the page', async () => {
    vi.useFakeTimers()
    const store = useRestaurantCatalog()
    await store.load()
    vi.mocked(fetchRestaurants).mockClear()
    store.filters.keyword = '未发出的搜索'
    await settle()
    store.cancelPending()
    await vi.advanceTimersByTimeAsync(300)
    expect(fetchRestaurants).not.toHaveBeenCalled()
  })

  /** 连续加载仍使用服务端页码；用真实批次区间验证追加、重试和返回，而非重复的首批夹具。 */
  function mobilePages(total = 45): void {
    vi.mocked(fetchRestaurants).mockImplementation(async (_filters, page, size) =>
      pageFixture({
        page,
        pageSize: size,
        total,
        totalPages: Math.ceil(total / size),
        records: Array.from(
          { length: Math.max(0, Math.min(size, total - (page - 1) * size)) },
          (_, index) => ({
            ...restaurantFixture,
            id: String((page - 1) * size + index + 1),
          }),
        ),
      }),
    )
  }

  it('appends mobile batches in server order, deduplicates IDs and stops at the actual end', async () => {
    mobilePages()
    const store = useRestaurantCatalog()
    store.activate(true)
    await settle()
    expect(store.visibleRestaurants).toHaveLength(20)
    expect(store.pageSize).toBe(20)
    expect(store.hasMore).toBe(true)
    await store.loadMore()
    expect(store.visibleRestaurants.map((row) => row.id)).toEqual(
      Array.from({ length: 40 }, (_, index) => String(index + 1)),
    )
    vi.mocked(fetchRestaurants).mockResolvedValueOnce(
      pageFixture({
        total: 45,
        totalPages: 3,
        records: [
          { ...restaurantFixture, id: '40' },
          { ...restaurantFixture, id: '41' },
          { ...restaurantFixture, id: '41' },
        ],
      }),
    )
    await store.loadMore()
    expect(store.visibleRestaurants).toHaveLength(41)
    expect(store.visibleRestaurants.at(-1)?.id).toBe('41')
    expect(store.hasMore).toBe(false)
    expect(store.atEnd).toBe(true)
    await store.loadMore()
    expect(fetchRestaurants).toHaveBeenCalledTimes(3)
  })

  it('keeps cards on append failure and retries the same page without concurrent duplicates', async () => {
    mobilePages()
    const store = useRestaurantCatalog()
    store.activate(true)
    await settle()
    let reject!: (reason: Error) => void
    vi.mocked(fetchRestaurants).mockImplementationOnce(
      () =>
        new Promise((_resolve, fail) => {
          reject = fail
        }),
    )
    const pending = store.loadMore()
    await store.loadMore()
    expect(fetchRestaurants).toHaveBeenCalledTimes(2)
    reject(new Error('网络中断'))
    await pending
    expect(store.visibleRestaurants).toHaveLength(20)
    expect(store.error).toBeNull()
    expect(store.moreError).toBe('网络中断')
    await store.loadMore()
    expect(fetchRestaurants).toHaveBeenLastCalledWith(
      expect.anything(),
      2,
      20,
      expect.any(AbortSignal),
    )
    expect(store.visibleRestaurants).toHaveLength(40)
    expect(store.moreError).toBeNull()
  })

  it('invalidates an append immediately on filter change even if cancellation is ignored', async () => {
    mobilePages()
    const store = useRestaurantCatalog()
    store.activate(true)
    await settle()
    let resolve!: (result: RestaurantPage) => void
    vi.mocked(fetchRestaurants).mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done
        }),
    )
    const pending = store.loadMore()
    const signal = vi.mocked(fetchRestaurants).mock.calls.at(-1)![3]!
    store.filters.category = 'HOT_POT'
    expect(signal.aborted).toBe(true)
    resolve(pageFixture({ records: [{ ...restaurantFixture, id: 'stale' }] }))
    await pending
    await settle()
    expect(store.visibleRestaurants).toHaveLength(20)
    expect(store.visibleRestaurants.some((row) => row.id === 'stale')).toBe(false)
    expect(fetchRestaurants).toHaveBeenLastCalledWith(
      expect.objectContaining({ category: 'HOT_POT' }),
      1,
      20,
      expect.any(AbortSignal),
    )
  })

  it('restores successful mobile batches on return and resumes an interrupted batch', async () => {
    mobilePages()
    const store = useRestaurantCatalog()
    store.activate(true)
    await settle()
    await store.loadMore()
    let resolve!: (result: RestaurantPage) => void
    vi.mocked(fetchRestaurants).mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done
        }),
    )
    const pending = store.loadMore()
    store.pause()
    resolve(pageFixture({ records: [{ ...restaurantFixture, id: 'stale' }] }))
    await pending
    const calls = vi.mocked(fetchRestaurants).mock.calls.length
    store.activate(true)
    await settle()
    expect(fetchRestaurants).toHaveBeenCalledTimes(calls)
    expect(store.visibleRestaurants).toHaveLength(40)
    await store.loadMore()
    expect(fetchRestaurants).toHaveBeenLastCalledWith(
      expect.anything(),
      3,
      20,
      expect.any(AbortSignal),
    )
    expect(store.visibleRestaurants).toHaveLength(45)
  })

  it('starts fresh after writes or filters changed while another page owned the catalog', async () => {
    mobilePages()
    const store = useRestaurantCatalog()
    store.activate(true)
    await settle()
    await store.loadMore()
    store.pause()
    store.invalidate()
    store.activate(true)
    await settle()
    expect(store.visibleRestaurants).toHaveLength(20)
    store.pause()
    store.filters.category = 'HOT_POT'
    await settle()
    const calls = vi.mocked(fetchRestaurants).mock.calls.length
    store.activate(true)
    await settle()
    expect(fetchRestaurants).toHaveBeenCalledTimes(calls + 1)
    expect(fetchRestaurants).toHaveBeenLastCalledWith(
      expect.objectContaining({ category: 'HOT_POT' }),
      1,
      20,
      expect.any(AbortSignal),
    )
  })

  it('switches responsive modes with one request and never mixes page sizes', async () => {
    mobilePages()
    const store = useRestaurantCatalog()
    store.activate(true)
    await settle()
    await store.loadMore()
    store.activate(false)
    await settle()
    expect(store.visibleRestaurants).toHaveLength(8)
    store.goToPage(3)
    await settle()
    const calls = vi.mocked(fetchRestaurants).mock.calls.length
    store.activate(true)
    await settle()
    expect(fetchRestaurants).toHaveBeenCalledTimes(calls + 1)
    expect(fetchRestaurants).toHaveBeenLastCalledWith(
      expect.anything(),
      1,
      20,
      expect.any(AbortSignal),
    )
    expect(store.visibleRestaurants).toHaveLength(20)
  })

  it('does not treat the ES result window as the end of all matches', async () => {
    vi.mocked(fetchRestaurants).mockResolvedValue(
      pageFixture({ total: 10001, totalPages: 501, accessiblePages: 1 }),
    )
    const store = useRestaurantCatalog()
    store.activate(true)
    await settle()
    expect(store.hasMore).toBe(false)
    expect(store.atEnd).toBe(false)
    expect(store.searchNotice).toContain('请缩小筛选范围')
  })

  it('stops automatic progress on an unexpected empty batch but finishes when the total shrinks', async () => {
    mobilePages()
    const store = useRestaurantCatalog()
    store.activate(true)
    await settle()
    vi.mocked(fetchRestaurants).mockResolvedValueOnce(
      pageFixture({ records: [], total: 45, totalPages: 3 }),
    )
    await store.loadMore()
    expect(store.moreError).toContain('本批餐厅')
    expect(store.visibleRestaurants).toHaveLength(20)
    vi.mocked(fetchRestaurants).mockResolvedValueOnce(
      pageFixture({ records: [], total: 20, totalPages: 1 }),
    )
    await store.loadMore()
    expect(store.hasMore).toBe(false)
    expect(store.atEnd).toBe(true)
  })
})
