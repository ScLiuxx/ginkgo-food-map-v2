import { effectScope, ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchRestaurants, type RestaurantReadModel } from '@/modules/restaurant'
import { searchAMapPlaces, type AMapPlace } from '../sdk/places'
import { matchPlace, usePlaceSearch } from './placeSearch'
vi.mock('@/modules/restaurant', () => ({ fetchRestaurants: vi.fn() }))
vi.mock('../sdk/places', () => ({ searchAMapPlaces: vi.fn() }))
const place: AMapPlace = {
  id: 'poi-1',
  name: '测试餐厅',
  address: '',
  district: '',
  category: '餐饮',
  longitude: 104.1,
  latitude: 30.6,
}
const restaurant = {
  id: '1',
  name: place.name,
  longitude: place.longitude,
  latitude: place.latitude,
  needsLocation: false,
} as RestaurantReadModel

/** 同名跨店、歧义、网络失败及切换关键词必须与未收录区别处理。 */
describe('地点与本站匹配及搜索状态', () => {
  const scopes: ReturnType<typeof effectScope>[] = []
  afterEach(() => {
    scopes.splice(0).forEach((scope) => scope.stop())
    vi.useRealTimers()
    vi.resetAllMocks()
  })
  function setup() {
    vi.useFakeTimers()
    const keyword = ref('')
    const scope = effectScope()
    scopes.push(scope)
    const state = scope.run(() => usePlaceSearch(() => keyword.value))!
    vi.mocked(searchAMapPlaces).mockResolvedValue({ places: [place], total: 20 })
    vi.mocked(fetchRestaurants).mockResolvedValue({
      records: [restaurant],
      total: 1,
      totalPages: 1,
      page: 1,
      pageSize: 100,
    })
    return { state, keyword, scope }
  }
  it('只匹配唯一同名且接近位置的记录，异地同名、缺位置和歧义不误连', () => {
    expect(matchPlace(place, [restaurant])?.id).toBe('1')
    expect(matchPlace(place, [{ ...restaurant, longitude: 105 }])).toBeNull()
    expect(matchPlace(place, [{ ...restaurant, needsLocation: true }])).toBeNull()
    expect(matchPlace(place, [restaurant, { ...restaurant, id: '2' }])).toBeNull()
  })
  it('无关键词不调用；500ms防抖、独立分页，并以无分类的附近候选核对收录状态', async () => {
    const { state, keyword } = setup()
    expect(searchAMapPlaces).not.toHaveBeenCalled()
    keyword.value = '测'
    keyword.value = '测试'
    await vi.advanceTimersByTimeAsync(499)
    expect(searchAMapPlaces).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    expect(searchAMapPlaces).toHaveBeenCalledTimes(1)
    expect(state.records.value[0]?.matchState).toBe('matched')
    expect(vi.mocked(fetchRestaurants).mock.calls[0]![0]).toMatchObject({
      keyword: '',
      category: '',
      hideClosed: false,
    })
    expect(vi.mocked(fetchRestaurants).mock.calls[0]![4]).toHaveProperty('west')
    state.goToPage(2)
    await vi.advanceTimersByTimeAsync(0)
    expect(vi.mocked(searchAMapPlaces).mock.calls.at(-1)![1]).toBe(2)
    keyword.value = ''
    await vi.advanceTimersByTimeAsync(1000)
    expect(state.records.value).toHaveLength(0)
    expect(state.total.value).toBe(0)
  })
  it('匹配读取失败或候选被截断不冒充未收录', async () => {
    const { state, keyword } = setup()
    vi.mocked(fetchRestaurants).mockRejectedValue(new Error('offline'))
    keyword.value = '测试'
    await vi.advanceTimersByTimeAsync(500)
    expect(state.records.value[0]?.matchState).toBe('unavailable')
    vi.mocked(fetchRestaurants).mockResolvedValue({
      records: [restaurant],
      total: 101,
      totalPages: 2,
      page: 1,
      pageSize: 100,
    })
    await state.load()
    await vi.advanceTimersByTimeAsync(0)
    expect(state.records.value[0]?.matchState).toBe('unavailable')
  })
  it('切换关键词废弃忽略取消的旧搜索响应，卸载取消待执行搜索', async () => {
    const { state, keyword, scope } = setup()
    let finish!: (result: Awaited<ReturnType<typeof searchAMapPlaces>>) => void
    vi.mocked(searchAMapPlaces).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    keyword.value = '旧'
    await vi.advanceTimersByTimeAsync(500)
    keyword.value = '新'
    await vi.advanceTimersByTimeAsync(500)
    finish({ places: [{ ...place, id: 'old' }], total: 99 })
    await vi.advanceTimersByTimeAsync(0)
    expect(state.records.value[0]?.id).toBe('poi-1')
    keyword.value = '等待'
    scope.stop()
    await vi.advanceTimersByTimeAsync(500)
    expect(searchAMapPlaces).toHaveBeenCalledTimes(2)
  })
  it('旧关键词的匹配请求晚到不能修改新关键词的收录状态', async () => {
    const { state, keyword } = setup()
    let finish!: (result: Awaited<ReturnType<typeof fetchRestaurants>>) => void
    vi.mocked(fetchRestaurants).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    keyword.value = '旧'
    await vi.advanceTimersByTimeAsync(500)
    expect(state.records.value[0]?.matchState).toBe('checking')
    keyword.value = '新'
    await vi.advanceTimersByTimeAsync(500)
    finish({
      records: [{ ...restaurant, id: 'old' }],
      total: 1,
      page: 1,
      pageSize: 100,
      totalPages: 1,
    })
    await vi.advanceTimersByTimeAsync(0)
    expect(state.records.value[0]?.match?.id).toBe('1')
  })
})
