import { afterEach, describe, expect, it, vi } from 'vitest'
import { httpClient } from '@/shared/api/httpClient'
import {
  fetchNotifications,
  markNotificationRead,
  markAllNotificationsRead,
  deleteNotification,
  type NotificationItem,
  type NotificationPage,
} from './notificationsApi'

const page = (): NotificationPage => ({
  userId: '42',
  category: 'SYSTEM',
  page: 1,
  pageSize: 10,
  total: 1,
  totalPages: 1,
  unreadCount: 1,
  unreadCounts: { SYSTEM: 1, COMMENTS: 0 },
  latestSystem: null,
  records: [
    {
      id: '9007199254740993',
      requestId: '9007199254740995',
      category: 'SYSTEM',
      sourceId: '9007199254740995',
      reviewId: null,
      actorUserId: null,
      actorUsername: null,
      requestType: 'UPDATE',
      decision: 'REJECTED',
      restaurantName: '测试店',
      rejectReason: '请补充地址',
      createdAt: '2026-11-07T08:00:00Z',
      readAt: null,
    },
  ],
})
afterEach(() => vi.restoreAllMocks())
/** 校验私有身份回执、BIGINT精度和固定写路径，不把损坏数据当成空收件箱。 */
describe('通知接口契约', () => {
  it('按固定身份和消息ID删除，只接受204成功回执', async () => {
    const signal = new AbortController().signal
    const remove = vi.spyOn(httpClient, 'delete').mockResolvedValue({ status: 204 })
    await deleteNotification('9007199254740993', 'original-session', signal)
    expect(remove).toHaveBeenCalledWith('/api/me/notifications/9007199254740993', {
      signal,
      headers: { Authorization: 'Bearer original-session' },
    })
    for (const id of ['01', '0', '9223372036854775808']) {
      await expect(deleteNotification(id, 'original-session', signal)).rejects.toThrow()
    }
    expect(remove).toHaveBeenCalledTimes(1)
    remove.mockResolvedValueOnce({ status: 200 })
    await expect(deleteNotification('101', 'original-session', signal)).rejects.toThrow(
      '删除结果无法确认',
    )
  })
  it('关注通知独立分类统计，不含点评或审核快照', async () => {
    const source: NotificationPage = {
      ...page(),
      category: 'FOLLOWERS',
      unreadCounts: { SYSTEM: 0, COMMENTS: 0, FOLLOWERS: 1 },
      records: [
        {
          ...page().records[0]!,
          notificationType: 'USER_FOLLOWED',
          category: 'FOLLOWERS',
          requestId: null,
          requestType: null,
          decision: null,
          restaurantName: null,
          rejectReason: null,
          actorUserId: '88',
          actorUsername: '新食友',
        },
      ],
    }
    const get = vi
      .spyOn(httpClient, 'get')
      .mockResolvedValue({ data: { success: true, data: source } })
    const result = await fetchNotifications(
      'FOLLOWERS',
      1,
      '42',
      'token',
      new AbortController().signal,
    )
    expect(result.unreadCounts.FOLLOWERS).toBe(1)
    get.mockResolvedValueOnce({
      data: {
        success: true,
        data: { ...source, records: [{ ...source.records[0], reviewId: '1' }] },
      },
    })
    await expect(
      fetchNotifications('FOLLOWERS', 1, '42', 'token', new AbortController().signal),
    ).rejects.toThrow('无法确认')
  })
  it('评论消息只含来源与回复者，分类统计和系统摘要独立保留', async () => {
    const source = page()
    source.category = 'COMMENTS'
    source.unreadCounts = { SYSTEM: 2, COMMENTS: 1 }
    source.records = [
      {
        ...source.records[0]!,
        category: 'COMMENTS',
        sourceId: '501',
        reviewId: '301',
        actorUserId: '88',
        actorUsername: '回复者',
        requestId: null,
        requestType: null,
        decision: null,
        restaurantName: null,
        rejectReason: null,
      },
    ]
    vi.spyOn(httpClient, 'get').mockResolvedValue({ data: { success: true, data: source } })
    expect(
      await fetchNotifications('COMMENTS', 1, '42', 'token', new AbortController().signal),
    ).toEqual(source)
  })
  it.each(['scope', 'counts', 'mixed'])('拒绝跨分类或混合消息%s', async (kind) => {
    const source = page()
    if (kind === 'scope') source.category = 'COMMENTS'
    if (kind === 'counts') source.unreadCounts.SYSTEM = 0
    if (kind === 'mixed') source.records[0]!.reviewId = '301'
    vi.spyOn(httpClient, 'get').mockResolvedValue({ data: { success: true, data: source } })
    await expect(
      fetchNotifications('SYSTEM', 1, '42', 'token', new AbortController().signal),
    ).rejects.toThrow('无法确认')
  })
  it('只传分类分页，保留大ID并丢弃额外隐私字段', async () => {
    const source = page(),
      signal = new AbortController().signal
    const get = vi.spyOn(httpClient, 'get').mockResolvedValue({
      data: {
        success: true,
        data: {
          ...source,
          email: 'hidden',
          records: [{ ...source.records[0], sourceHash: 'hidden' }],
        },
      },
    })
    expect(await fetchNotifications('SYSTEM', 1, '42', 'fixed-token', signal)).toEqual(source)
    expect(get).toHaveBeenCalledExactlyOnceWith('/api/me/notifications', {
      params: { category: 'SYSTEM', page: 1, pageSize: 10 },
      signal,
      headers: { Authorization: 'Bearer fixed-token' },
    })
  })
  it.each(['owner', 'category', 'page', 'unread', 'duplicate', 'date', 'decision', 'reason', 'id'])(
    '拒绝损坏的 %s 回执',
    async (kind) => {
      const source = page()
      if (kind === 'owner') source.userId = '99'
      if (kind === 'category') source.category = 'ALL'
      if (kind === 'page') source.page = 2
      if (kind === 'unread') source.unreadCount = 2
      if (kind === 'duplicate') {
        source.records.push({ ...source.records[0]! })
        source.total = 2
      }
      if (kind === 'date') source.records[0]!.createdAt = 'invalid'
      if (kind === 'decision') source.records[0]!.decision = 'APPROVED'
      if (kind === 'reason') source.records[0]!.rejectReason = null
      if (kind === 'id') source.records[0]!.id = '9223372036854775808'
      vi.spyOn(httpClient, 'get').mockResolvedValue({ data: { success: true, data: source } })
      await expect(
        fetchNotifications('SYSTEM', 1, '42', 'token', new AbortController().signal),
      ).rejects.toThrow('无法确认')
    },
  )
  it('单条与全部已读固定凭证和分类，不接受任意跳转或消息路径', async () => {
    const post = vi.spyOn(httpClient, 'post').mockResolvedValue({ status: 204 }),
      signal = new AbortController().signal
    await markNotificationRead('9007199254740993', 'token', signal)
    await markAllNotificationsRead('SYSTEM', 'token', signal)
    expect(post).toHaveBeenNthCalledWith(1, '/api/me/notifications/9007199254740993/read', null, {
      signal,
      headers: { Authorization: 'Bearer token' },
    })
    expect(post).toHaveBeenNthCalledWith(2, '/api/me/notifications/read-all', null, {
      params: { category: 'SYSTEM' },
      signal,
      headers: { Authorization: 'Bearer token' },
    })
    await expect(markNotificationRead('../42', 'token', signal)).rejects.toThrow('无法确认')
    expect(post).toHaveBeenCalledTimes(2)
  })
})

/** 举报结果复用系统分类，但只允许携带所属点评、举报来源和处置结论。 */
it('举报结果接受REMOVED/DISMISSED并拒绝混入审核字段', async () => {
  const source = page()
  const reportResult: NotificationItem = {
    ...source.records[0]!,
    id: '7001',
    sourceId: '55',
    reviewId: '301',
    notificationType: 'REPORT_RESULT',
    requestId: null,
    requestType: null,
    decision: 'REMOVED',
    restaurantName: null,
    rejectReason: null,
  }
  source.records = [reportResult]
  source.latestSystem = reportResult
  const get = vi
    .spyOn(httpClient, 'get')
    .mockResolvedValue({ data: { success: true, data: source } })
  const signal = new AbortController().signal
  expect(await fetchNotifications('SYSTEM', 1, '42', 'token', signal)).toEqual(source)

  get.mockResolvedValue({
    data: {
      success: true,
      data: {
        ...source,
        records: [{ ...reportResult, decision: 'DISMISSED' }],
        latestSystem: { ...reportResult, decision: 'DISMISSED' },
      },
    },
  })
  expect(
    (await fetchNotifications('SYSTEM', 1, '42', 'token', signal)).records[0]?.decision,
  ).toBe('DISMISSED')

  for (const patch of [
    { requestId: '9007199254740995' },
    { requestType: 'UPDATE' },
    { decision: 'APPROVED' },
    { reviewId: null },
    { restaurantName: '混入店名' },
    { announcementId: '12' },
  ]) {
    get.mockResolvedValueOnce({
      data: {
        success: true,
        data: {
          ...source,
          records: [{ ...reportResult, ...patch }],
          latestSystem: { ...reportResult, ...patch },
        },
      },
    })
    await expect(fetchNotifications('SYSTEM', 1, '42', 'token', signal)).rejects.toThrow(
      '无法确认',
    )
  }
})

/** 公告与业务通知使用不同ID空间，不能误调用审核或其他来源。 */
it('公告列表核验带类型ID，支持公告已读和删除的固定路径', async () => {
  const source = page()
  const announcement = {
    ...source.records[0]!,
    id: 'announcement:12',
    notificationType: 'ANNOUNCEMENT',
    sourceId: '12',
    announcementId: '12',
    announcementTitle: '全体公告',
    requestId: null,
    requestType: null,
    decision: null,
    restaurantName: null,
    rejectReason: null,
  }
  source.records = [announcement]
  source.latestSystem = announcement
  const get = vi
    .spyOn(httpClient, 'get')
    .mockResolvedValue({ data: { success: true, data: source } })
  const signal = new AbortController().signal
  const data = await fetchNotifications('SYSTEM', 1, '42', 'token', signal)
  expect(data.records[0]?.announcementTitle).toBe('全体公告')
  const post = vi.spyOn(httpClient, 'post').mockResolvedValue({ status: 204 })
  const remove = vi.spyOn(httpClient, 'delete').mockResolvedValue({ status: 204 })
  await markNotificationRead('announcement:12', 'token', signal)
  await deleteNotification('announcement:12', 'token', signal)
  expect(post).toHaveBeenCalledWith('/api/me/notifications/announcement:12/read', null, {
    signal,
    headers: { Authorization: 'Bearer token' },
  })
  expect(remove).toHaveBeenCalledWith('/api/me/notifications/announcement:12', {
    signal,
    headers: { Authorization: 'Bearer token' },
  })
  get.mockResolvedValue({
    data: { success: true, data: { ...source, records: [{ ...announcement, id: '12' }] } },
  })
  await expect(fetchNotifications('SYSTEM', 1, '42', 'token', signal)).rejects.toThrow()
})
