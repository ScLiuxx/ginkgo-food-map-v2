import { afterEach, describe, expect, it, vi } from 'vitest'
import { httpClient } from '@/shared/api/httpClient'
import { fetchNotificationReview, fetchReviewDetail } from './reviewDetailApi'

const original = () => ({
  restaurantId: '1',
  author: { id: '42', username: '原作者', level: 2 },
  review: {
    id: '9007199254740993',
    scores: { taste: 7.5, value: 8, service: 8, ambience: 8 },
    overallScore: 7.88,
    recommendation: 'NEUTRAL',
    body: '历史原文',
    createdAt: '2026-11-14T01:00:00Z',
    scoringStatus: 'history',
    photos: [{ id: '501', position: 0, url: '/api/reviews/9007199254740993/photos/0' }],
  },
})
const like = (reply = false) => ({
  userId: '42',
  likeId: '81',
  targetType: reply ? 'REPLY' : 'REVIEW',
  targetId: reply ? '301' : '9007199254740993',
  reviewId: '9007199254740993',
  restaurantId: '1',
  author: { id: '42', username: '本人' },
  body: '获赞的原内容',
  createdAt: '2026-11-14T02:00:00Z',
})
const envelope = (data: unknown) => ({ data: { success: true, data } })
afterEach(() => vi.restoreAllMocks())
/** 不从通知摘要猜目标，精确接口保留大ID、历史身份、父级和照片白名单。 */
describe('单条点评及通知目标读取', () => {
  it('固定凭证直接读取历史ID并投影完整点评，不遍历店铺或作者分页', async () => {
    const data = original(),
      signal = new AbortController().signal
    const get = vi
      .spyOn(httpClient, 'get')
      .mockResolvedValue(envelope({ ...data, requestHash: 'secret' }))
    const detail = await fetchReviewDetail(data.review.id, 'original-token', signal)
    expect(detail.item.id).toBe(data.review.id)
    expect(detail.item.scoringStatus).toBe('history')
    expect(detail.item.photos[0]?.url).toBe(data.review.photos[0]?.url)
    expect(detail).not.toHaveProperty('requestHash')
    expect(get).toHaveBeenCalledExactlyOnceWith('/api/reviews/9007199254740993', {
      signal,
      headers: { Authorization: 'Bearer original-token' },
    })
  })
  it('作者等级为空时仍可展示，评分字段只保留四维公开数据', async () => {
    const data = original()
    vi.spyOn(httpClient, 'get').mockResolvedValue(
      envelope({
        ...data,
        author: { ...data.author, level: null },
        review: { ...data.review, scores: { ...data.review.scores, privateField: 'secret' } },
      }),
    )
    const result = await fetchReviewDetail(data.review.id, 'token', new AbortController().signal)
    expect(result.item.authorLevel).toBeUndefined()
    expect(result.item.scores).toEqual(data.review.scores)
  })
  it.each(['wrong-id', 'wrong-parent', 'bad-photo', 'null-photo', 'bad-score', 'bad-status'])(
    '拒绝损坏的详情：%s',
    async (kind) => {
      const data = original()
      if (kind === 'wrong-id') data.review.id = '2'
      if (kind === 'wrong-parent') data.restaurantId = ''
      if (kind === 'bad-photo') data.review.photos[0]!.url = 'https://example.test/private'
      if (kind === 'null-photo')
        data.review.photos[0] = null as unknown as (typeof data.review.photos)[number]
      if (kind === 'bad-score') data.review.scores.taste = 7.3
      if (kind === 'bad-status') data.review.scoringStatus = 'unknown'
      vi.spyOn(httpClient, 'get').mockResolvedValue(envelope(data))
      await expect(
        fetchReviewDetail('9007199254740993', 'token', new AbortController().signal),
      ).rejects.toThrow('无法确认')
    },
  )
  it('回复获赞可定位本人在他人点评下的回复，不要求原点评属于本人', async () => {
    const data = original()
    data.author.id = '88'
    vi.spyOn(httpClient, 'get')
      .mockResolvedValueOnce(envelope(like(true)))
      .mockResolvedValueOnce(envelope(data))
    const result = await fetchNotificationReview(
      'REPLY_LIKED',
      '81',
      data.review.id,
      '42',
      'token',
      new AbortController().signal,
    )
    expect(result.item.authorId).toBe('88')
    expect(result.focusedReply).toEqual({
      id: '301',
      author: { id: '42', username: '本人' },
      body: '获赞的原内容',
      createdAt: '2026-11-14T02:00:00Z',
    })
  })
  it('评论重新鉴权后定位指定回复，忽略摘要中的任意路径', async () => {
    const data = original(),
      target = {
        userId: '42',
        restaurantId: '1',
        reviewId: data.review.id,
        replyId: '301',
        reviewAuthor: { id: '42', username: '本人' },
        replyAuthor: { id: '88', username: '回复者' },
        review: { ...data.review },
        replyBody: '通知对应回复',
        replyCreatedAt: '2026-11-14T02:00:00Z',
        url: 'https://example.test',
      }
    const get = vi
      .spyOn(httpClient, 'get')
      .mockResolvedValueOnce(envelope(target))
      .mockResolvedValueOnce(envelope(data))
    const result = await fetchNotificationReview(
      'COMMENT',
      '301',
      data.review.id,
      '42',
      'token',
      new AbortController().signal,
    )
    expect(get.mock.calls[0]?.[0]).toBe('/api/me/review-replies/301/target')
    expect(result.focusedReply?.id).toBe('301')
    expect(result.focusedReply?.body).toBe('通知对应回复')
  })
  it.each(['owner', 'source', 'review', 'target', 'type', 'parent', 'author'])(
    '拒绝错配通知或详情：%s',
    async (kind) => {
      const target = like(),
        data = original()
      if (kind === 'owner') target.userId = '99'
      if (kind === 'source') target.likeId = '99'
      if (kind === 'review') target.reviewId = '99'
      if (kind === 'target') target.targetId = '99'
      if (kind === 'type') target.targetType = 'REPLY'
      if (kind === 'parent') data.restaurantId = '99'
      if (kind === 'author') data.author.id = '99'
      const get = vi
        .spyOn(httpClient, 'get')
        .mockResolvedValueOnce(envelope(target))
        .mockResolvedValueOnce(envelope(data))
      await expect(
        fetchNotificationReview(
          'REVIEW_LIKED',
          '81',
          data.review.id,
          '42',
          'token',
          new AbortController().signal,
        ),
      ).rejects.toThrow('无法确认')
      expect(get).toHaveBeenCalledTimes(['parent', 'author'].includes(kind) ? 2 : 1)
    },
  )
  it('源已删除时停止，不继续读取或跳转到最新点评', async () => {
    const get = vi.spyOn(httpClient, 'get').mockRejectedValue(new Error('内容已删除'))
    await expect(
      fetchNotificationReview(
        'REVIEW_LIKED',
        '81',
        '9007199254740993',
        '42',
        'token',
        new AbortController().signal,
      ),
    ).rejects.toThrow('已删除')
    expect(get).toHaveBeenCalledTimes(1)
  })
})
