import { afterEach, describe, expect, it, vi } from 'vitest'
import { httpClient } from '@/shared/api/httpClient'
import { clearAccessToken, setAccessToken } from '@/shared/api/authSession'
import {
  publishTextReview,
  textReviewGateway,
  fetchReviewPage,
  fetchReviewHistory,
  fetchReplies,
  publishReply,
  deleteReply,
} from './reviewApi'
import type { ReviewDraft } from '../model/review'

const draft = (): ReviewDraft => ({
  restaurantId: '101',
  clientRequestId: 'e88a495b-8524-43f6-ab9e-7e7276686798',
  scores: { taste: 8, value: 7.5, service: 7, ambience: 8 },
  recommended: true,
  body: '用餐体验很不错',
  photos: [],
})
const receipt = { reviewId: '9007199254740993', restaurantId: '101', replayed: false }

/** 实际B1协议夹具，固定作者身份和有序照片，避免客户端按名称重新归组。 */
const readPayload = () => ({
  page: 1,
  pageSize: 8,
  totalGroups: 1,
  totalReviews: 2,
  totalPages: 1,
  records: [
    {
      author: { id: '22', username: '点评同学' },
      historyCount: 1,
      currentReview: {
        id: '9007199254740993',
        scores: draft().scores,
        overallScore: 7.63,
        recommended: true,
        body: '<script>纯文本正文</script>',
        createdAt: '2026-10-30T01:00:00Z',
        scoringStatus: 'current',
        photos: Array.from({ length: 5 }, (_, i) => ({
          id: String(100 - i),
          position: i,
          url: `/api/reviews/9007199254740993/photos/${i}`,
        })),
      },
    },
  ],
})
afterEach(() => {
  vi.restoreAllMocks()
  clearAccessToken()
})

describe('text review HTTP integration', () => {
  it('deletes a reply with a fixed credential and rejects unconfirmed receipts', async () => {
    const remove = vi.spyOn(httpClient, 'delete').mockResolvedValue({ status: 204 })
    const controller = new AbortController()
    await expect(deleteReply('10', controller.signal)).rejects.toThrow('请先登录')
    expect(remove).not.toHaveBeenCalled()
    setAccessToken('reply-author-session')
    await deleteReply('9007199254740993', controller.signal)
    expect(remove).toHaveBeenCalledWith('/api/review-replies/9007199254740993', {
      headers: { Authorization: 'Bearer reply-author-session' },
      signal: controller.signal,
    })
    remove.mockResolvedValueOnce({ status: 200 })
    await expect(deleteReply('10', controller.signal)).rejects.toThrow('删除未确认')
    remove.mockRejectedValueOnce(new Error('服务繁忙'))
    await expect(deleteReply('10', controller.signal)).rejects.toThrow('删除未确认')
  })
  it('deletes the exact review with the current credential and propagates failures', async () => {
    setAccessToken('author-session')
    const remove = vi.spyOn(httpClient, 'delete').mockResolvedValue({ status: 204 })
    await textReviewGateway.remove('9007199254740993')
    expect(remove).toHaveBeenCalledWith('/api/reviews/9007199254740993', {
      headers: { Authorization: 'Bearer author-session' },
    })
    remove.mockRejectedValueOnce(new Error('服务繁忙'))
    await expect(textReviewGateway.remove('10')).rejects.toThrow('删除未确认')
  })
  it('maps author groups and all five photos without inferring permissions', async () => {
    const get = vi
      .spyOn(httpClient, 'get')
      .mockResolvedValue({ data: { success: true, data: readPayload() } })
    const signal = new AbortController().signal
    const result = await fetchReviewPage('101', signal)
    expect(get).toHaveBeenCalledWith('/api/restaurants/101/reviews', {
      params: { page: 1, pageSize: 8, sort: 'DEFAULT' },
      signal,
    })
    expect(result).toMatchObject({ totalGroups: 1, totalReviews: 2 })
    expect(result.records[0]).toMatchObject({
      id: '9007199254740993',
      authorId: '22',
      historyCount: 1,
      overallScore: 7.63,
      canDelete: false,
      replies: [],
      photos: [{ id: '100' }, { id: '99' }, { id: '98' }, { id: '97' }, { id: '96' }],
    })
    expect(result.records[0]).not.toHaveProperty('previousReviews')
  })
  it('sends time sorting and pagination to the server', async () => {
    const get = vi
      .spyOn(httpClient, 'get')
      .mockResolvedValue({ data: { success: true, data: readPayload() } })
    const signal = new AbortController().signal
    await fetchReviewPage('101', signal, 1, 'TIME')
    expect(get).toHaveBeenCalledWith('/api/restaurants/101/reviews', {
      params: { page: 1, pageSize: 8, sort: 'TIME' },
      signal,
    })
  })
  it('surfaces failures and rejects malformed groups or foreign photo URLs', async () => {
    const get = vi.spyOn(httpClient, 'get').mockRejectedValueOnce(new Error('网络中断'))
    await expect(fetchReviewPage('101', new AbortController().signal)).rejects.toThrow('网络中断')
    const badPhoto = readPayload()
    badPhoto.records[0]!.currentReview.photos[0]!.url = 'https://example.test/foreign.jpg'
    for (const data of [null, { ...readPayload(), records: null }, badPhoto]) {
      get.mockResolvedValueOnce({ data: { success: true, data } })
      await expect(fetchReviewPage('101', new AbortController().signal)).rejects.toThrow('响应格式')
    }
  })
  it('publishes ordered uploaded photo IDs without uploading file bytes again', async () => {
    setAccessToken('session')
    const post = vi
      .spyOn(httpClient, 'post')
      .mockResolvedValue({ data: { success: true, data: receipt } })
    await publishTextReview({
      ...draft(),
      photos: [new File(['x'], 'one.jpg'), new File(['y'], 'two.jpg')],
      photoIds: ['22', '11'],
    })
    expect(post.mock.calls[0]![1]).toMatchObject({ photoIds: ['22', '11'] })
    expect(post.mock.calls[0]![1]).not.toHaveProperty('photos')
  })
  it('sends only supported fields and fixes the initiating account token', async () => {
    setAccessToken('initiating-session')
    const post = vi.spyOn(httpClient, 'post').mockImplementation(async () => {
      setAccessToken('another-session')
      return { data: { success: true, data: receipt } }
    })
    expect(await publishTextReview(draft())).toEqual(receipt)
    expect(post).toHaveBeenCalledWith(
      '/api/restaurants/101/reviews',
      {
        clientRequestId: draft().clientRequestId,
        scores: draft().scores,
        recommended: true,
        body: draft().body,
      },
      { headers: { Authorization: 'Bearer initiating-session' } },
    )
  })
  it('keeps one request ID across failures and accepts an idempotent replay receipt', async () => {
    setAccessToken('session')
    const post = vi
      .spyOn(httpClient, 'post')
      .mockRejectedValueOnce(new Error('timeout'))
      .mockResolvedValueOnce({ data: { success: true, data: { ...receipt, replayed: true } } })
    const input = draft()
    await expect(publishTextReview(input)).rejects.toThrow('同内容重试不会重复发布')
    expect((await publishTextReview(input)).replayed).toBe(true)
    expect(post.mock.calls[0]![1]).toEqual(post.mock.calls[1]![1])
  })
  it('rejects photos and missing login before making a request', async () => {
    const post = vi.spyOn(httpClient, 'post')
    await expect(publishTextReview(draft())).rejects.toThrow('请先登录')
    setAccessToken('session')
    await expect(
      publishTextReview({ ...draft(), photos: [new File(['x'], 'test.jpg')] }),
    ).rejects.toThrow('移除照片')
    expect(post).not.toHaveBeenCalled()
  })
  it('does not turn malformed success envelopes into confirmed publication', async () => {
    setAccessToken('session')
    const post = vi.spyOn(httpClient, 'post')
    for (const data of [null, { ...receipt, restaurantId: '102' }, { ...receipt, reviewId: 42 }]) {
      post.mockResolvedValueOnce({ data: { success: true, data } })
      await expect(publishTextReview(draft())).rejects.toThrow('发布结果无法确认')
    }
  })
  it('opens replies and requires authentication for deletion', async () => {
    const get = vi.spyOn(httpClient, 'get')
    expect(textReviewGateway.supportsReplies).toBe(true)
    expect(textReviewGateway.listReplies).toBe(fetchReplies)
    await expect(textReviewGateway.remove('1')).rejects.toThrow('请先登录')
    expect(get).not.toHaveBeenCalled()
  })
})

describe('reply HTTP integration', () => {
  const page = () => ({
    records: [
      {
        id: '9007199254740993',
        author: { id: '11', username: '同学' },
        body: '<b>纯文本</b>',
        createdAt: '2026-10-30T01:00:00Z',
      },
    ],
    hasMore: false,
    nextCursor: null,
  })
  it('reads a separate page with the original cursor and cancellation signal', async () => {
    const get = vi
      .spyOn(httpClient, 'get')
      .mockResolvedValue({ data: { success: true, data: page() } })
    const signal = new AbortController().signal
    expect(await fetchReplies('10', signal, 'cursor')).toEqual(page())
    expect(get).toHaveBeenCalledWith('/api/reviews/10/replies', {
      params: { pageSize: 10, cursor: 'cursor' },
      signal,
    })
  })
  it('rejects malformed pages, duplicate IDs and nonadvancing cursors', async () => {
    const get = vi.spyOn(httpClient, 'get')
    for (const data of [
      null,
      { ...page(), records: [page().records[0], page().records[0]] },
      { ...page(), hasMore: true, nextCursor: 'cursor' },
      { ...page(), nextCursor: 'extra' },
      { ...page(), records: [{ ...page().records[0], author: null }] },
    ]) {
      get.mockResolvedValueOnce({ data: { success: true, data } })
      await expect(fetchReplies('10', new AbortController().signal, 'cursor')).rejects.toThrow(
        '响应格式',
      )
    }
  })
  it('pins the initiating token and only confirms a matching receipt', async () => {
    setAccessToken('initiator')
    const post = vi.spyOn(httpClient, 'post').mockImplementation(async () => {
      setAccessToken('new-session')
      return {
        data: {
          success: true,
          data: { replyId: '9007199254740993', reviewId: '10', replayed: true },
        },
      }
    })
    await expect(publishReply('10', '回复', 'uuid')).resolves.toMatchObject({ replayed: true })
    expect(post).toHaveBeenCalledWith(
      '/api/reviews/10/replies',
      { body: '回复', clientRequestId: 'uuid' },
      { headers: { Authorization: 'Bearer initiator' }, signal: undefined },
    )
    post.mockResolvedValueOnce({
      data: { success: true, data: { replyId: '1', reviewId: '11', replayed: false } },
    })
    await expect(publishReply('10', '回复', 'uuid')).rejects.toThrow('文字已保留')
    clearAccessToken()
    await expect(publishReply('10', '回复', 'uuid')).rejects.toThrow('请先登录')
  })
})

/** 历史接口不能混入主卡、重复记录或外部照片地址。 */
describe('history HTTP integration', () => {
  const payload = () => ({
    author: { id: '22', username: '点评同学' },
    currentReviewId: '100',
    records: [{ ...readPayload().records[0]!.currentReview, scoringStatus: 'history' }],
    hasMore: false,
    nextCursor: null,
  })
  it('forwards cursor and abort signal and retains historical photo attribution', async () => {
    const get = vi
      .spyOn(httpClient, 'get')
      .mockResolvedValue({ data: { success: true, data: payload() } })
    const signal = new AbortController().signal
    const result = await fetchReviewHistory('101', '22', signal, 'abc')
    expect(get).toHaveBeenCalledWith('/api/restaurants/101/reviews/authors/22/history', {
      params: { pageSize: 5, cursor: 'abc' },
      signal,
    })
    expect(result.records[0]).toMatchObject({
      authorId: '22',
      scoringStatus: 'history',
      canDelete: false,
    })
    expect(result.records[0]!.photos).toHaveLength(5)
  })
  it('rejects duplicate/current records, cross-author response and invalid continuation', async () => {
    const p = payload()
    const cases = [
      { ...p, currentReviewId: p.records[0]!.id },
      { ...p, records: [...p.records, ...p.records] },
      { ...p, author: { id: '33', username: 'other' } },
      { ...p, hasMore: true },
      {
        ...p,
        records: [
          {
            ...p.records[0],
            photos: [{ id: '1', position: 0, url: 'https://example.test/private' }],
          },
        ],
      },
    ]
    const get = vi.spyOn(httpClient, 'get')
    for (const data of cases) {
      get.mockResolvedValueOnce({ data: { success: true, data } })
      await expect(fetchReviewHistory('101', '22', new AbortController().signal)).rejects.toThrow(
        '响应格式',
      )
    }
  })
})
