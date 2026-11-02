import { createApp, reactive, nextTick } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useViewport } from './viewport'
import { fetchRestaurants } from '@/modules/restaurant'
import { fetchMapPoints } from '../api/mapApi'

vi.mock('@/modules/restaurant', () => ({ fetchRestaurants: vi.fn() }))
vi.mock('../api/mapApi', () => ({ fetchMapPoints: vi.fn() }))

/** 使用可控时钟和乱序Promise验证真实竞态；不依赖高德账号或网络。 */
describe('地图自动查询', () => {
  let unmount: () => void = () => {}
  afterEach(() => {
    unmount()
    vi.useRealTimers()
    vi.resetAllMocks()
  })
  function setup() {
    vi.useFakeTimers()
    const filters = reactive({
      keyword: '',
      category: '',
      sort: 'score' as const,
      hideClosed: false,
      maxPrice: null,
      maxDistanceMeters: null,
    })
    let state!: ReturnType<typeof useViewport>
    const node = globalThis.document.createElement('div')
    const app = createApp({
      setup() {
        state = useViewport(filters)
        return () => null
      },
    })
    app.mount(node)
    unmount = () => app.unmount()
    vi.mocked(fetchRestaurants).mockResolvedValue({
      records: [],
      total: 0,
      totalPages: 0,
      page: 1,
      pageSize: 8,
    })
    vi.mocked(fetchMapPoints).mockResolvedValue({
      points: [],
      matchedCount: 0,
      returnedCount: 0,
      limit: 500,
      truncated: false,
    })
    return { state, filters }
  }
  const area = { west: 104, east: 104.2, south: 30.6, north: 30.8 }
  it('连续移动及重复事件只请求最终范围，300ms前不请求', async () => {
    const { state } = setup()
    state.move(area)
    state.move({ ...area, east: 104.3 })
    state.move({ ...area, east: 104.3 })
    await vi.advanceTimersByTimeAsync(299)
    expect(fetchRestaurants).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    expect(fetchRestaurants).toHaveBeenCalledTimes(1)
    expect(fetchMapPoints).toHaveBeenCalledTimes(1)
    expect(vi.mocked(fetchRestaurants).mock.calls[0]?.[4]?.east).toBe(104.3)
  })
  it('区域不变的列表翻页不重新读取点位，筛选变化回第一页', async () => {
    const { state, filters } = setup()
    vi.mocked(fetchRestaurants).mockResolvedValue({
      records: [],
      total: 20,
      totalPages: 3,
      page: 1,
      pageSize: 8,
    })
    state.move(area)
    await vi.advanceTimersByTimeAsync(300)
    state.goToPage(2)
    await nextTick()
    await vi.advanceTimersByTimeAsync(0)
    expect(state.page.value).toBe(2)
    expect(fetchMapPoints).toHaveBeenCalledTimes(1)
    filters.keyword = '火锅'
    await vi.advanceTimersByTimeAsync(300)
    expect(state.page.value).toBe(1)
    expect(fetchMapPoints).toHaveBeenCalledTimes(1)
    expect(vi.mocked(fetchRestaurants).mock.calls.at(-1)![4]).toBeUndefined()
  })
  it('有关键词时跨视口搜索，移动保留页码，清空后按最新地图范围浏览', async () => {
    const { state, filters } = setup()
    filters.keyword = '餐厅'
    state.move(area)
    await vi.advanceTimersByTimeAsync(300)
    expect(fetchMapPoints).not.toHaveBeenCalled()
    expect(vi.mocked(fetchRestaurants).mock.calls[0]![4]).toBeUndefined()
    const calls = vi.mocked(fetchRestaurants).mock.calls.length
    state.move({ ...area, east: 104.3 })
    await vi.advanceTimersByTimeAsync(500)
    expect(fetchRestaurants).toHaveBeenCalledTimes(calls)
    filters.keyword = ''
    await vi.advanceTimersByTimeAsync(300)
    expect(vi.mocked(fetchRestaurants).mock.calls.at(-1)![4]?.east).toBe(104.3)
    expect(fetchMapPoints).toHaveBeenCalledTimes(1)
  })
  it('移动立刻废弃旧响应，即使旧请求忽略abort也不能覆盖新结果', async () => {
    const { state } = setup()
    let finish!: (value: Awaited<ReturnType<typeof fetchRestaurants>>) => void
    vi.mocked(fetchRestaurants).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    state.move(area)
    await vi.advanceTimersByTimeAsync(300)
    state.move({ ...area, east: 104.4 })
    await vi.advanceTimersByTimeAsync(300)
    finish({ records: [], total: 999, totalPages: 125, page: 1, pageSize: 8 })
    await vi.advanceTimersByTimeAsync(0)
    expect(state.total.value).toBe(0)
  })
  it('失败保留旧结果并标记错误，卸载取消待执行查询', async () => {
    const { state } = setup()
    vi.mocked(fetchRestaurants).mockResolvedValueOnce({
      records: [],
      total: 20,
      totalPages: 3,
      page: 1,
      pageSize: 8,
    })
    state.move(area)
    await vi.advanceTimersByTimeAsync(300)
    vi.mocked(fetchRestaurants).mockRejectedValueOnce(new Error('offline'))
    state.move({ ...area, east: 104.5 })
    await vi.advanceTimersByTimeAsync(300)
    expect(state.total.value).toBe(20)
    expect(state.error.value).toBeTruthy()
    state.move(area)
    unmount()
    unmount = () => {}
    await vi.advanceTimersByTimeAsync(300)
    expect(fetchRestaurants).toHaveBeenCalledTimes(2)
  })
})
