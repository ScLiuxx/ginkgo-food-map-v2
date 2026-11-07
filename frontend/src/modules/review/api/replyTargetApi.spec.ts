import { afterEach, describe, expect, it, vi } from 'vitest'
import { httpClient } from '@/shared/api/httpClient'
import { fetchReplyTarget, type ReplyTarget } from './replyTargetApi'
const target = (): ReplyTarget => ({
  userId: '42',
  restaurantId: '1',
  reviewId: '301',
  replyId: '9007199254740997',
  reviewAuthor: { id: '42', username: '作者' },
  replyAuthor: { id: '88', username: '回复者' },
  review: {
    id: '301',
    body: '原文',
    createdAt: '2026-11-01T00:00:00Z',
    overallScore: 7,
    recommended: true,
  },
  replyBody: '回复',
  replyCreatedAt: '2026-11-07T00:00:00Z',
})
afterEach(() => vi.restoreAllMocks())
/** 目标读取必须绑定本人、具体点评与回复，不从任意URL或错误回执填充内容。 */
describe('评论目标读取契约', () => {
  it('保留大ID，固定身份头，只投影当前页面需要的正文', async () => {
    const data = target(),
      signal = new AbortController().signal
    const get = vi
      .spyOn(httpClient, 'get')
      .mockResolvedValue({ data: { success: true, data: { ...data, requestHash: 'hidden' } } })
    expect(await fetchReplyTarget(data.replyId, data.reviewId, '42', 'token', signal)).toEqual(data)
    expect(get).toHaveBeenCalledWith('/api/me/review-replies/9007199254740997/target', {
      signal,
      headers: { Authorization: 'Bearer token' },
    })
  })
  it.each(['owner', 'reply', 'review', 'author', 'body', 'time'])('拒绝错配%s', async (kind) => {
    const data = target()
    if (kind === 'owner') data.userId = '99'
    if (kind === 'reply') data.replyId = '3'
    if (kind === 'review') data.review.id = '9'
    if (kind === 'author') data.reviewAuthor.id = '99'
    if (kind === 'body') data.replyBody = null as unknown as string
    if (kind === 'time') data.replyCreatedAt = 'bad'
    vi.spyOn(httpClient, 'get').mockResolvedValue({ data: { success: true, data } })
    await expect(
      fetchReplyTarget('9007199254740997', '301', '42', 'token', new AbortController().signal),
    ).rejects.toThrow('无法确认')
  })
})
