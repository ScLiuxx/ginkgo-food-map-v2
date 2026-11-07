import { afterEach, describe, expect, it, vi } from 'vitest'
import { httpClient } from '@/shared/api/httpClient'
import { fetchMyReviews } from './myReviewApi'

const payload = () => ({
  author: { id: '42', username: '我的账号' },
  page: 1,
  pageSize: 8,
  totalGroups: 1,
  totalReviews: 2,
  totalPages: 1,
  records: [
    {
      restaurant: { id: '1', name: '店铺', businessStatus: 'CLOSED' },
      historyCount: 1,
      nextHistoryCursor: 'cursor',
      currentReview: {
        id: '102',
        scores: { taste: 0, value: 0, service: 0, ambience: 0 },
        overallScore: 0,
        recommended: false,
        body: '零分也是真实体验',
        createdAt: '2026-11-07T00:00:00Z',
        scoringStatus: 'current',
        photos: [{ id: '501', position: 0, url: '/api/reviews/102/photos/0' }],
      },
    },
  ],
})
afterEach(() => vi.restoreAllMocks())

/** 校验私有响应的账号归属、分页及照片地址，不能因为200响应就接受串号或任意图片URL。 */
describe('本人点评传输', () => {
  it('只发送当前页和固定凭证，保留零分及有序照片，不扫描其他页面', async () => {
    const get = vi
      .spyOn(httpClient, 'get')
      .mockResolvedValue({ data: { success: true, data: payload() } })
    const signal = new AbortController().signal
    const result = await fetchMyReviews(1, '42', 'fixed-session', signal)
    expect(get).toHaveBeenCalledExactlyOnceWith('/api/me/reviews', {
      params: { page: 1, pageSize: 8 },
      signal,
      headers: { Authorization: 'Bearer fixed-session' },
    })
    expect(result.records[0]?.item.overallScore).toBe(0)
    expect(result.records[0]?.item.authorId).toBe('42')
    expect(result.records[0]?.item.photos).toEqual([
      { id: '501', url: '/api/reviews/102/photos/0' },
    ])
    expect(result.records[0]?.restaurant.businessStatus).toBe('CLOSED')
  })
  it('公开列表固定被查看用户，保留作者等级，不发送访问者为作者', async () => {
    const data = { ...payload(), author: { id: '22', username: '被查看者', level: 3 } }
    const get = vi.spyOn(httpClient, 'get').mockResolvedValue({ data: { success: true, data } })
    const signal = new AbortController().signal
    const result = await fetchMyReviews(1, '22', 'viewer-session', signal, '22')
    expect(get).toHaveBeenCalledWith('/api/users/22/reviews', {
      params: { page: 1, pageSize: 8 },
      signal,
      headers: { Authorization: 'Bearer viewer-session' },
    })
    expect(result.records[0]?.item.authorId).toBe('22')
    expect(result.records[0]?.item.authorLevel).toBe(3)
  })
  it.each(['owner', 'duplicateStore', 'photoUrl', 'cursor', 'pagination', 'score', 'deletedStore'])(
    '拒绝归属或协议异常：%s',
    async (kind) => {
      const data = payload()
      if (kind === 'owner') data.author.id = '99'
      if (kind === 'duplicateStore') data.records.push(structuredClone(data.records[0]!))
      if (kind === 'photoUrl')
        data.records[0]!.currentReview.photos[0]!.url = 'https://untrusted.example/image.jpg'
      if (kind === 'cursor') data.records[0]!.nextHistoryCursor = ''
      if (kind === 'pagination') data.totalPages = 2
      if (kind === 'score') data.records[0]!.currentReview.scores.taste = Number.NaN
      if (kind === 'deletedStore') data.records[0]!.restaurant.businessStatus = 'DELETED'
      vi.spyOn(httpClient, 'get').mockResolvedValue({ data: { success: true, data } })
      await expect(
        fetchMyReviews(1, '42', 'fixed-session', new AbortController().signal),
      ).rejects.toThrow('数据无法确认')
    },
  )
  it('保留真实越界空页元数据，网络失败不回填空列表', async () => {
    const data = { ...payload(), page: 2, records: [] }
    const get = vi
      .spyOn(httpClient, 'get')
      .mockResolvedValueOnce({ data: { success: true, data } })
      .mockRejectedValueOnce(new Error('网络失败'))
    const result = await fetchMyReviews(2, '42', 'session', new AbortController().signal)
    expect(result.page).toBe(2)
    expect(result.totalGroups).toBe(1)
    await expect(fetchMyReviews(2, '42', 'session', new AbortController().signal)).rejects.toThrow(
      '网络失败',
    )
    expect(get).toHaveBeenCalledTimes(2)
  })
})
