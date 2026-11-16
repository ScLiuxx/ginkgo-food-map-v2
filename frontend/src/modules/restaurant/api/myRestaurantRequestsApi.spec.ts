import { afterEach, describe, expect, it, vi } from 'vitest'
import { httpClient } from '@/shared/api/httpClient'
import {
  fetchMyRequestPage,
  fetchMyRequestDetail,
  fetchMyRequestPhoto,
  fetchMyRequestReviewPhoto,
  editMyRequest,
  withdrawMyRequest,
  resubmitMyRequest,
  type MyRequestPage,
  type MyRequestDetail,
} from './myRestaurantRequestsApi'

const row = {
  id: '101',
  restaurantId: null,
  requestType: 'CREATE' as const,
  status: 'PENDING' as const,
  name: '申请店名',
  createdAt: '2026-11-01T12:00:00Z',
  reviewedAt: null,
  photoCount: 0,
}
const page = (): MyRequestPage => ({
  userId: '42',
  records: [{ ...row }],
  counts: { ALL: 1, PENDING: 1, APPROVED: 0, REJECTED: 0 },
  total: 1,
  totalPages: 1,
  page: 1,
  pageSize: 10,
})
const detail = (): MyRequestDetail => ({
  userId: '42',
  application: { ...row },
  proposed: { name: '申请店名' },
  approved: null,
  rejectReason: null,
  targetAvailable: false,
  photoPositions: [],
})
const signal = () => new AbortController().signal
afterEach(() => vi.restoreAllMocks())

/** 传输契约验证身份/申请绑定和白名单，图片只使用本站固定路径，不跟随响应中的任意URL。 */
describe('本人投稿传输', () => {
  it('读取私有点评与后续申请仅保留白名单，不暴露图片ID和内部摘要', async () => {
    const source = {
      ...detail(),
      resubmittedRequestId: '102',
      reviewSubmission: {
        id: '81',
        status: 'WAITING',
        publishedReviewId: null,
        scores: { taste: 7.5, value: 7, service: 7, ambience: 7, secret: 'private' },
        recommendation: 'NEUTRAL',
        body: '这是本人提交的点评内容。',
        photoPositions: [0],
        photoIds: ['secret'],
        requestHash: 'private',
      },
    }
    vi.spyOn(httpClient, 'get').mockResolvedValue({ data: { success: true, data: source } })
    const result = await fetchMyRequestDetail('101', '42', 'token', signal())
    expect(result.resubmittedRequestId).toBe('102')
    expect(result.reviewSubmission).toMatchObject({
      scores: { taste: 7.5 },
      recommendation: 'NEUTRAL',
    })
    expect(result.reviewSubmission).not.toHaveProperty('photoIds')
    expect(result.reviewSubmission?.scores).not.toHaveProperty('secret')
    source.reviewSubmission.recommendation = 'unknown'
    await expect(fetchMyRequestDetail('101', '42', 'token', signal())).rejects.toThrow('无法确认')
  })
  it('点评图片走独立私有路径，必须是有效JPEG', async () => {
    const blob = new Blob(['jpeg'], { type: 'image/jpeg' })
    const get = vi.spyOn(httpClient, 'get').mockResolvedValue({ data: blob })
    const abort = signal()
    expect(await fetchMyRequestReviewPhoto('101', 0, 'token', abort)).toBe(blob)
    expect(get).toHaveBeenCalledWith(
      '/api/me/restaurant-requests/101/review/photos/0',
      expect.objectContaining({
        signal: abort,
        headers: { Authorization: 'Bearer token' },
        responseType: 'blob',
      }),
    )
    get.mockResolvedValueOnce({ data: new Blob(['html'], { type: 'text/html' }) })
    await expect(fetchMyRequestReviewPhoto('101', 0, 'token', signal())).rejects.toThrow('无法读取')
  })
  it('修改、撤回、重提固定凭证并校验申请回执，不把旧ID当作新申请', async () => {
    const put = vi
      .spyOn(httpClient, 'put')
      .mockResolvedValue({ data: { success: true, data: { id: '101', status: 'PENDING' } } })
    const post = vi
      .spyOn(httpClient, 'post')
      .mockResolvedValueOnce({ data: { success: true, data: { id: '101', status: 'WITHDRAWN' } } })
      .mockResolvedValueOnce({ data: { success: true, data: { id: '102', status: 'PENDING' } } })
    const input = {
      name: '修改店铺',
      categoryCode: 'OTHER',
      restaurantType: 'REGULAR' as const,
      priceKind: 'UNKNOWN' as const,
      pricePerPerson: null,
      address: null,
      latitude: 30.75,
      longitude: 103.93,
      locationConfirmed: true as const,
      businessStatus: 'OPERATING' as const,
      tags: [],
      supportsDineIn: null,
      supportsDelivery: null,
      photoIds: [],
    }
    await editMyRequest('101', input, 'old-token')
    expect(put).toHaveBeenCalledWith('/api/me/restaurant-requests/101', input, {
      headers: { Authorization: 'Bearer old-token' },
    })
    await withdrawMyRequest('101', 'old-token')
    const request = { ...input, clientRequestId: '550e8400-e29b-41d4-a716-446655440000' }
    expect(await resubmitMyRequest('101', request, 'old-token')).toEqual({
      id: '102',
      status: 'PENDING',
    })
    expect(post).toHaveBeenLastCalledWith('/api/me/restaurant-requests/101/resubmit', request, {
      headers: { Authorization: 'Bearer old-token' },
    })
    post.mockResolvedValueOnce({ data: { success: true, data: { id: '101', status: 'PENDING' } } })
    await expect(withdrawMyRequest('101', 'token')).rejects.toThrow('无法确认')
  })
  it('保留已收录处理方式及点评回执，拒绝把该方式放入待审结果', async () => {
    const source = detail()
    source.application = {
      ...source.application,
      status: 'APPROVED',
      restaurantId: '991',
      approvalMode: 'LINKED_EXISTING',
      reviewedAt: '2026-11-14T06:00:00Z',
    }
    source.approved = { name: '已有餐厅' }
    source.targetAvailable = true
    source.reviewSubmission = { status: 'PUBLISHED', publishedReviewId: '601' }
    vi.spyOn(httpClient, 'get').mockResolvedValue({ data: { success: true, data: source } })
    const result = await fetchMyRequestDetail('101', '42', 'token', signal())
    expect(result.application.approvalMode).toBe('LINKED_EXISTING')
    expect(result.reviewSubmission).toEqual({ status: 'PUBLISHED', publishedReviewId: '601' })
    source.application.status = 'PENDING'
    await expect(fetchMyRequestDetail('101', '42', 'token', signal())).rejects.toThrow('无法确认')
  })

  it('列表有已撤回记录时仍能读取其他处理结果', async () => {
    const source = page()
    source.counts = { ALL: 2, PENDING: 1, APPROVED: 0, REJECTED: 0, WITHDRAWN: 1 }
    source.total = 2
    source.records.push({ ...row, id: '102', status: 'WITHDRAWN' })
    vi.spyOn(httpClient, 'get').mockResolvedValue({ data: { success: true, data: source } })
    const result = await fetchMyRequestPage('ALL', 1, '42', 'token', signal())
    expect(result.records[1]?.status).toBe('WITHDRAWN')
    expect(result.counts.WITHDRAWN).toBe(1)
  })
  it('列表仅发送状态分页及固定凭证，并丢弃未知字段', async () => {
    const source = page()
    const get = vi.spyOn(httpClient, 'get').mockResolvedValue({
      data: {
        success: true,
        data: { ...source, email: 'private', records: [{ ...row, requestHash: 'private' }] },
      },
    })
    const requestSignal = signal()
    expect(await fetchMyRequestPage('ALL', 1, '42', 'old-token', requestSignal)).toEqual(source)
    expect(get).toHaveBeenCalledExactlyOnceWith('/api/me/restaurant-requests', {
      params: { status: 'ALL', page: 1, pageSize: 10 },
      signal: requestSignal,
      headers: { Authorization: 'Bearer old-token' },
    })
  })
  it.each(['owner', 'page', 'total', 'count', 'duplicate', 'status'])(
    '拒绝损坏列表 %s',
    async (kind) => {
      const source = page()
      if (kind === 'owner') source.userId = '99'
      if (kind === 'page') source.page = 2
      if (kind === 'total') source.total = 0
      if (kind === 'count') source.counts.ALL = 10
      if (kind === 'duplicate') {
        source.records.push({ ...row })
        source.total = source.counts.ALL = source.counts.PENDING = 2
      }
      if (kind === 'status') source.records[0]!.status = 'APPROVED'
      vi.spyOn(httpClient, 'get').mockResolvedValue({ data: { success: true, data: source } })
      await expect(fetchMyRequestPage('ALL', 1, '42', 'token', signal())).rejects.toThrow(
        '无法确认',
      )
    },
  )
  it('详情丢弃快照内部字段与管理员资料，校验请求ID', async () => {
    vi.spyOn(httpClient, 'get').mockResolvedValue({
      data: {
        success: true,
        data: {
          ...detail(),
          before: { name: '不能展示' },
          reviewedBy: '33',
          proposed: { name: '申请店名', photoIds: ['901'], clientRequestId: 'private' },
        },
      },
    })
    const result = await fetchMyRequestDetail('101', '42', 'token', signal())
    expect(result.proposed.name).toBe('申请店名')
    expect(result).not.toHaveProperty('before')
    expect(result).not.toHaveProperty('reviewedBy')
    expect(result.proposed).not.toHaveProperty('photoIds')
    await expect(fetchMyRequestDetail('102', '42', 'token', signal())).rejects.toThrow('无法确认')
  })
  it.each(['owner', 'positions', 'result', 'target'])('拒绝无法确认的详情 %s', async (kind) => {
    const source = detail()
    if (kind === 'owner') source.userId = '99'
    if (kind === 'positions') source.photoPositions = [0, 0]
    if (kind === 'result') source.application.status = 'REJECTED'
    if (kind === 'target') source.targetAvailable = true
    vi.spyOn(httpClient, 'get').mockResolvedValue({ data: { success: true, data: source } })
    await expect(fetchMyRequestDetail('101', '42', 'token', signal())).rejects.toThrow('无法确认')
  })
  it('私有JPEG固定路径、凭证和Blob类型，非法位置不发请求', async () => {
    const blob = new Blob(['jpeg'], { type: 'image/jpeg' }),
      requestSignal = signal()
    const get = vi.spyOn(httpClient, 'get').mockResolvedValue({ data: blob })
    expect(await fetchMyRequestPhoto('101', 0, 'fixed-token', requestSignal)).toBe(blob)
    expect(get).toHaveBeenCalledExactlyOnceWith('/api/me/restaurant-requests/101/photos/0', {
      signal: requestSignal,
      headers: { Authorization: 'Bearer fixed-token' },
      responseType: 'blob',
    })
    await expect(fetchMyRequestPhoto('101', 5, 'token', signal())).rejects.toThrow('无法确认')
    expect(get).toHaveBeenCalledTimes(1)
    get.mockResolvedValueOnce({ data: new Blob(['error'], { type: 'application/json' }) })
    await expect(fetchMyRequestPhoto('101', 0, 'token', signal())).rejects.toThrow('无法读取')
  })
})
