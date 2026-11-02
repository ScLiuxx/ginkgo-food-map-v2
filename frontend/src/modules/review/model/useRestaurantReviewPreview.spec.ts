import { effectScope, nextTick, ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchReviewPage } from '../api/reviewApi'
import { useRestaurantReviewPreview } from './useRestaurantReviewPreview'
import type { ReviewPage } from './review'

vi.mock('../api/reviewApi', () => ({ fetchReviewPage: vi.fn() }))
afterEach(() => vi.resetAllMocks())

/** 验证首页辅助读取不会伪造评价数，也不会被切店前的晚到响应覆盖。 */
describe('restaurant review preview', () => {
  const page = (totalReviews: number): ReviewPage => ({
    records: [],
    totalReviews,
    totalGroups: 0,
    totalPages: 0,
    page: 1,
    pageSize: 8,
  })

  it('uses genuine total and marks illustrative photos without internal environment wording', async () => {
    const data = page(12)
    data.records = [
      { photos: [] },
      {
        photos: [{ id: '1', url: '/api/reviews/5/photos/0' }],
        body: '联调模拟，非真实体验',
      },
    ] as ReviewPage['records']
    vi.mocked(fetchReviewPage).mockResolvedValue(data)
    const scope = effectScope()
    const preview = scope.run(() => useRestaurantReviewPreview(ref('20')))!
    await vi.waitFor(() => expect(preview.count.value).toBe(12))
    expect(preview.photo.value).toEqual({
      url: '/api/reviews/5/photos/0',
      label: '示意配图 · 非本店实拍',
    })
    expect(fetchReviewPage).toHaveBeenCalledTimes(1)
    scope.stop()
  })

  it('cancels previous reads and ignores late responses after a restaurant switch or disposal', async () => {
    let resolve!: (data: ReviewPage) => void
    vi.mocked(fetchReviewPage)
      .mockReturnValueOnce(
        new Promise((r) => {
          resolve = r
        }),
      )
      .mockResolvedValueOnce(page(3))
    const id = ref('20')
    const scope = effectScope()
    const preview = scope.run(() => useRestaurantReviewPreview(id))!
    const firstSignal = vi.mocked(fetchReviewPage).mock.calls[0]![1]
    id.value = '21'
    await nextTick()
    expect(firstSignal.aborted).toBe(true)
    await vi.waitFor(() => expect(preview.count.value).toBe(3))
    scope.stop()
    resolve(page(99))
    await nextTick()
    expect(preview.count.value).toBe(3)
    expect(vi.mocked(fetchReviewPage).mock.calls[1]![1].aborted).toBe(true)
  })

  it('distinguishes unavailable data from a genuine empty page', async () => {
    vi.mocked(fetchReviewPage)
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(page(0))
    const id = ref('20')
    const scope = effectScope()
    const preview = scope.run(() => useRestaurantReviewPreview(id))!
    await vi.waitFor(() => expect(preview.state.value).toBe('error'))
    expect(preview.count.value).toBeNull()
    id.value = '21'
    await vi.waitFor(() => expect(preview.state.value).toBe('ready'))
    expect(preview.count.value).toBe(0)
    expect(preview.photo.value).toBeNull()
    scope.stop()
  })
})
