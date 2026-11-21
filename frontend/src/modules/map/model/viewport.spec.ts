import { createApp, reactive } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useViewport, type ViewportSnapshot } from './viewport'
import type { CatalogFilters } from '@/modules/restaurant'
import { mapExamples } from './map'
import { fetchMapPoints, type MapPoints } from '../api/mapApi'

vi.mock('../api/mapApi', () => ({ fetchMapPoints: vi.fn() }))

/** 检查集合一致、范围约束及真实乱序响应，避免地图和列表重新分成两条查询链路。 */
describe('以地图范围为核心的统一候选集', () => {
  let unmount = () => {}
  afterEach(() => {
    unmount()
    vi.useRealTimers()
    vi.resetAllMocks()
  })
  const area = { west: 104, east: 104.2, south: 30.6, north: 30.8 }
  function batch(count = 0, total = count): MapPoints {
    const records = Array.from({ length: count }, (_, index) => ({
      ...mapExamples[0]!,
      id: String(index + 1),
      needsLocation: false,
      latitude: 30.7,
      longitude: 104.1,
    }))
    return {
      records,
      points: records.map((row) => ({
        id: row.id,
        name: row.name,
        latitude: row.latitude,
        longitude: row.longitude,
        score: row.score,
        pricePerPerson: row.price,
        businessStatus: row.closed ? 'CLOSED' : 'OPERATING',
      })),
      matchedCount: total,
      returnedCount: count,
      limit: 100,
      truncated: total > count,
    }
  }
  function setup(enabled = true, initial?: ViewportSnapshot) {
    vi.useFakeTimers()
    const filters = reactive<CatalogFilters>({
      keyword: '',
      category: '',
      sort: 'score',
      hideClosed: false,
      maxPrice: null,
      maxDistanceMeters: null,
    })
    let state!: ReturnType<typeof useViewport>
    const app = createApp({
      setup() {
        state = useViewport(filters, enabled, initial)
        return () => null
      },
    })
    app.mount(document.createElement('div'))
    unmount = () => app.unmount()
    vi.mocked(fetchMapPoints).mockResolvedValue(batch())
    return { state, filters }
  }
  it('SDK尚未提供范围时不查询全站，预览也不请求真实数据', async () => {
    const { state, filters } = setup()
    await state.load()
    filters.keyword = '火锅'
    await vi.advanceTimersByTimeAsync(300)
    expect(fetchMapPoints).not.toHaveBeenCalled()
    state.move(area)
    await vi.advanceTimersByTimeAsync(300)
    expect(fetchMapPoints).toHaveBeenCalledTimes(1)
    unmount()
    const preview = setup(false)
    preview.state.move(area)
    await preview.state.load()
    await vi.advanceTimersByTimeAsync(300)
    expect(fetchMapPoints).toHaveBeenCalledTimes(1)
  })
  it('300ms防抖后只请求最后范围，并保留类型、用餐方式和预算筛选', async () => {
    const { state, filters } = setup()
    filters.restaurantType = 'CANTEEN'
    filters.diningMode = 'dineIn'
    filters.maxPrice = 30
    filters.hasReviews = false
    state.move(area)
    state.move({ ...area, east: 104.3 })
    state.move({ ...area, east: 104.3 })
    await vi.advanceTimersByTimeAsync(299)
    expect(fetchMapPoints).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    expect(fetchMapPoints).toHaveBeenCalledTimes(1)
    expect(vi.mocked(fetchMapPoints).mock.calls[0]?.slice(0, 2)).toEqual([
      expect.objectContaining({
        restaurantType: 'CANTEEN',
        diningMode: 'dineIn',
        maxPrice: 30,
        hasReviews: false,
      }),
      { ...area, east: 104.3 },
    ])
  })
  it('同一次响应保留相同的100家及顺序，完整数量与截断标记独立显示', async () => {
    const { state } = setup()
    vi.mocked(fetchMapPoints).mockResolvedValue(batch(100, 150))
    state.move(area)
    await vi.advanceTimersByTimeAsync(300)
    expect(state.rows.value.map((row) => row.id)).toEqual(
      state.points.value.map((point) => point.id),
    )
    expect(state.rows.value).toHaveLength(100)
    expect(state.total.value).toBe(150)
    expect(state.truncated.value).toBe(true)
    expect(state.resultsCurrent.value).toBe(true)
  })
  it('关键词搜索受视口约束，拖动后按新区域重搜，清空关键词仍保留范围', async () => {
    const { state, filters } = setup()
    filters.keyword = '火锅'
    state.move(area)
    await vi.advanceTimersByTimeAsync(300)
    expect(fetchMapPoints).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: '火锅' }),
      area,
      expect.anything(),
    )
    state.move({ ...area, east: 104.3 })
    expect(state.resultsCurrent.value).toBe(false)
    await vi.advanceTimersByTimeAsync(300)
    expect(fetchMapPoints).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: '火锅' }),
      { ...area, east: 104.3 },
      expect.anything(),
    )
    filters.keyword = ''
    await vi.advanceTimersByTimeAsync(300)
    expect(fetchMapPoints).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: '' }),
      { ...area, east: 104.3 },
      expect.anything(),
    )
  })
  it('移动立即取消旧响应，即使请求忽略abort也不能在防抖等待中覆盖新范围', async () => {
    const { state } = setup()
    let finish!: (value: MapPoints) => void
    vi.mocked(fetchMapPoints).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    state.move(area)
    await vi.advanceTimersByTimeAsync(300)
    const signal = vi.mocked(fetchMapPoints).mock.calls[0]![2]!
    state.move({ ...area, east: 104.4 })
    finish(batch(10, 999))
    await vi.advanceTimersByTimeAsync(0)
    expect(signal.aborted).toBe(true)
    expect(state.total.value).toBe(0)
    await vi.advanceTimersByTimeAsync(300)
    expect(state.total.value).toBe(0)
  })
  it('失败时地图与列表一起保留旧集合，重试成功后一起替换并恢复空状态', async () => {
    const { state } = setup()
    vi.mocked(fetchMapPoints).mockResolvedValueOnce(batch(3))
    state.move(area)
    await vi.advanceTimersByTimeAsync(300)
    vi.mocked(fetchMapPoints).mockRejectedValueOnce(new Error('offline'))
    state.move({ ...area, east: 104.5 })
    await vi.advanceTimersByTimeAsync(300)
    expect(state.rows.value).toHaveLength(3)
    expect(state.points.value).toHaveLength(3)
    expect(state.total.value).toBe(3)
    expect(state.error.value).toBeTruthy()
    expect(state.resultsCurrent.value).toBe(false)
    await state.load()
    expect(state.rows.value).toEqual([])
    expect(state.points.value).toEqual([])
    expect(state.resultsCurrent.value).toBe(true)
  })
  it('缺少距离参照坐标时同时清空两边，不发送不完整的距离查询', async () => {
    const { state, filters } = setup()
    vi.mocked(fetchMapPoints).mockResolvedValueOnce(batch(3))
    state.move(area)
    await vi.advanceTimersByTimeAsync(300)
    filters.sort = 'distance'
    await vi.advanceTimersByTimeAsync(300)
    expect(fetchMapPoints).toHaveBeenCalledTimes(1)
    expect(state.rows.value).toEqual([])
    expect(state.points.value).toEqual([])
    expect(state.total.value).toBe(0)
  })
  it('卸载取消待执行查询和在途请求', async () => {
    const { state } = setup()
    state.move(area)
    await vi.advanceTimersByTimeAsync(300)
    const signal = vi.mocked(fetchMapPoints).mock.calls[0]![2]!
    state.move({ ...area, east: 104.4 })
    unmount()
    unmount = () => {}
    await vi.advanceTimersByTimeAsync(300)
    expect(signal.aborted).toBe(true)
    expect(fetchMapPoints).toHaveBeenCalledTimes(1)
  })
  it('返回时立即恢复同批卡片和点位，按保存范围重查，失败保留再刷新', async () => {
    const cached = batch(3, 150)
    const { state } = setup(true, {
      bounds: area,
      total: 150,
      rows: cached.records,
      points: cached.points,
      truncated: true,
      searchNotice: '已保存提示',
      error: null,
    })
    expect(state.rows.value.map((row) => row.id)).toEqual(['1', '2', '3'])
    expect(state.points.value.map((point) => point.id)).toEqual(['1', '2', '3'])
    expect(state.total.value).toBe(150)
    expect(state.truncated.value).toBe(true)
    expect(fetchMapPoints).not.toHaveBeenCalled()
    vi.mocked(fetchMapPoints).mockRejectedValueOnce(new Error('offline'))
    await state.load()
    expect(fetchMapPoints).toHaveBeenLastCalledWith(expect.anything(), area, expect.anything())
    expect(state.snapshot().rows).toHaveLength(3)
    expect(state.snapshot().error).toBeTruthy()
    vi.mocked(fetchMapPoints).mockResolvedValueOnce(batch(1))
    await state.load()
    expect(state.rows.value).toHaveLength(1)
    expect(state.points.value).toHaveLength(1)
    expect(state.resultsCurrent.value).toBe(true)
  })
})
