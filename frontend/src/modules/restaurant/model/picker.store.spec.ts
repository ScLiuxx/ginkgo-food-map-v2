import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useRestaurantPicker } from './picker.store'
import { fetchPickerCandidates } from '../api/restaurantPickerApi'
import { restaurantFixture } from '../api/restaurant.test-data'
import type { RestaurantReadModel } from './restaurant.types'

vi.mock('../api/restaurantPickerApi', () => ({ fetchPickerCandidates: vi.fn() }))
const record = (id: string) => ({ ...restaurantFixture, id, name: `店铺${id}` })
const batch = (records: RestaurantReadModel[], remainingCount = 0) => ({
  records,
  remainingCount,
  matchedCount: 6,
})
function ready() {
  const store = useRestaurantPicker()
  Object.assign(store.draft, {
    range: 'QINGSHUIHE',
    distanceMeters: 3000,
    budget: '30',
    minRating: 'ANY',
    category: 'ANY',
    diningMode: 'ANY',
  })
  return store
}

/** 验证完整一轮的历史、预取失效和重试，不用固定随机结果验证公平性。 */
describe('独立选店会话', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    setActivePinia(createPinia())
  })
  it('不预选、不把未填写当不限；缺少用户位置不能抽取', async () => {
    const store = useRestaurantPicker()
    expect(store.complete).toBe(false)
    await store.start()
    expect(fetchPickerCandidates).not.toHaveBeenCalled()
    Object.assign(store.draft, {
      range: 'USER',
      distanceMeters: 1000,
      budget: 'ANY',
      minRating: 'ANY',
      category: 'ANY',
      diningMode: 'ANY',
    })
    expect(store.complete).toBe(true)
    await store.start()
    expect(store.error).toContain('定位')
    expect(fetchPickerCandidates).not.toHaveBeenCalled()
  })
  it('其余五项选齐仍需主动选择评分，不限也计为有效条件', async () => {
    const store = ready()
    store.draft.minRating = ''
    expect(store.completedCount).toBe(5)
    expect(store.complete).toBe(false)
    await store.start()
    expect(fetchPickerCandidates).not.toHaveBeenCalled()
    store.draft.minRating = 'ANY'
    expect(store.completedCount).toBe(6)
    expect(store.complete).toBe(true)
    vi.mocked(fetchPickerCandidates).mockResolvedValueOnce(batch([]))
    await store.start()
    expect(store.confirmed?.minRating).toBe('ANY')
  })
  it('预取后复核，本轮排除包含预取ID；回看不消耗新候选', async () => {
    const store = ready()
    vi.mocked(fetchPickerCandidates).mockResolvedValueOnce(
      batch([record('1'), record('2'), record('3')]),
    )
    await store.start()
    expect(store.current?.id).toBe('1')
    expect(store.reservedIds).toEqual(['1', '2', '3'])
    vi.mocked(fetchPickerCandidates).mockResolvedValueOnce(batch([record('2'), record('3')]))
    await store.next()
    expect(store.current?.id).toBe('2')
    expect(fetchPickerCandidates).toHaveBeenLastCalledWith(
      store.confirmed,
      ['1', '2', '3'],
      ['2', '3'],
      expect.any(AbortSignal),
    )
    vi.mocked(fetchPickerCandidates).mockResolvedValueOnce(batch([record('1')]))
    await store.visit(0)
    expect(store.current?.id).toBe('1')
    expect(store.history).toHaveLength(2)
    expect(store.queue.map((r) => r.id)).toEqual(['3'])
    vi.mocked(fetchPickerCandidates).mockResolvedValueOnce(batch([record('2')]))
    await store.next()
    expect(store.current?.id).toBe('2')
    expect(store.history).toHaveLength(2)
  })
  it('跨批不重复，失效预取不展示，全部用尽后保留历史', async () => {
    const store = ready()
    vi.mocked(fetchPickerCandidates)
      .mockResolvedValueOnce(batch([record('1'), record('2')], 1))
      .mockResolvedValueOnce(batch([], 1))
      .mockResolvedValueOnce(batch([record('3')]))
    await store.start()
    await store.next()
    expect(store.history.map((r) => r.id)).toEqual(['1', '3'])
    expect(store.reservedIds).toEqual(['1', '2', '3'])
    expect(store.exhausted).toBe(true)
    expect(fetchPickerCandidates).toHaveBeenLastCalledWith(
      store.confirmed,
      ['1', '2'],
      [],
      expect.any(AbortSignal),
    )
  })
  it('复核失败保留旧店与队列，重试成功才消耗；失效历史停止前往', async () => {
    const store = ready()
    vi.mocked(fetchPickerCandidates).mockResolvedValueOnce(batch([record('1'), record('2')]))
    await store.start()
    vi.mocked(fetchPickerCandidates).mockRejectedValueOnce(new Error('网络失败'))
    await store.next()
    expect(store.current?.id).toBe('1')
    expect(store.queue).toHaveLength(1)
    vi.mocked(fetchPickerCandidates).mockResolvedValueOnce(batch([record('2')]))
    await store.retry()
    expect(store.current?.id).toBe('2')
    vi.mocked(fetchPickerCandidates).mockResolvedValueOnce(batch([]))
    await store.visit(0)
    expect(store.current?.id).toBe('1')
    expect(store.unavailable).toBe(true)
    expect(store.history).toHaveLength(2)
  })
  it('改条件保留草稿，取消恢复原条件，确认清除历史；关闭全部清空', async () => {
    const store = ready()
    vi.mocked(fetchPickerCandidates).mockResolvedValue(batch([record('1')]))
    await store.start()
    store.edit()
    store.draft.budget = '15'
    store.draft.minRating = '4'
    await store.cancelEdit()
    expect(store.draft.budget).toBe('30')
    expect(store.draft.minRating).toBe('ANY')
    expect(store.history).toHaveLength(1)
    store.edit()
    store.draft.budget = '15'
    store.draft.minRating = '3.5'
    await store.start()
    expect(store.confirmed?.budget).toBe('15')
    expect(store.confirmed?.minRating).toBe('3.5')
    expect(store.history).toHaveLength(1)
    expect(fetchPickerCandidates).toHaveBeenLastCalledWith(
      store.confirmed,
      [],
      [],
      expect.any(AbortSignal),
    )
    store.reset()
    expect(store.completedCount).toBe(0)
    expect(store.history).toEqual([])
    expect(store.confirmed).toBeNull()
  })
  it('首次抽取中改条件再取消会继续原查询，并忽略被中断的结果', async () => {
    const store = ready()
    let resolve!: (value: ReturnType<typeof batch>) => void
    vi.mocked(fetchPickerCandidates)
      .mockReturnValueOnce(
        new Promise((r) => {
          resolve = r
        }),
      )
      .mockResolvedValueOnce(batch([record('2')]))
    const started = store.start()
    store.edit()
    store.draft.budget = '15'
    await store.cancelEdit()
    expect(store.draft.budget).toBe('30')
    expect(store.confirmed?.budget).toBe('30')
    expect(store.current?.id).toBe('2')
    expect(store.phase).toBe('results')
    expect(store.exhausted).toBe(true)
    resolve(batch([record('1')]))
    await started
    expect(store.history.map((r) => r.id)).toEqual(['2'])
  })
  it('关窗忽略迟到结果，快速连点只有一个请求', async () => {
    const store = ready()
    let resolve!: (value: ReturnType<typeof batch>) => void
    vi.mocked(fetchPickerCandidates).mockReturnValueOnce(
      new Promise((r) => {
        resolve = r
      }),
    )
    const started = store.start()
    await store.next()
    expect(fetchPickerCandidates).toHaveBeenCalledTimes(1)
    const signal = vi.mocked(fetchPickerCandidates).mock.calls[0]![3]!
    store.reset()
    expect(signal.aborted).toBe(true)
    resolve(batch([record('1')]))
    await started
    expect(store.history).toEqual([])
    expect(store.phase).toBe('conditions')
  })
})
