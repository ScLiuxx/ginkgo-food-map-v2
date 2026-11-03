import { effectScope, nextTick } from 'vue'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { setAccessToken, clearAccessToken } from '@/shared/api/authSession'
import {
  fetchRequestPage,
  fetchRequestDetail,
  fetchRequestPhoto,
  fetchReviewCategories,
  approveRequest,
  rejectRequest,
  type RequestItem,
  type RequestDetail,
  type RequestPage,
} from '../api/restaurantRequestsApi'
import { useRestaurantRequests } from './useRestaurantRequests'

vi.mock('../api/restaurantRequestsApi', () => ({
  fetchRequestPage: vi.fn(),
  fetchRequestDetail: vi.fn(),
  fetchRequestPhoto: vi.fn(),
  fetchReviewCategories: vi.fn(),
  approveRequest: vi.fn(),
  rejectRequest: vi.fn(),
}))
const item = (id: string): RequestItem => ({
  id,
  name: '真实申请' + id,
  requestType: 'CREATE',
  status: 'PENDING',
  restaurantId: null,
  submittedBy: '22',
  submitterName: '同学',
  createdAt: '2026-11-03T06:00:00Z',
  photoCount: 0,
})
const listing = (records = [item('11'), item('12')]): RequestPage => ({
  records,
  total: records.length,
  totalPages: records.length ? 1 : 0,
  page: 1,
  pageSize: 20,
  counts: { PENDING: records.length, APPROVED: 0, REJECTED: 0 },
})
const detail = (id: string): RequestDetail => ({
  application: item(id),
  proposed: {
    name: '真实申请' + id,
    supportsDineIn: false,
    pricePerPerson: 0,
    categoryCode: 'OTHER',
    priceKind: 'UNKNOWN',
    latitude: 30.7544,
    longitude: 103.9312,
    locationConfirmed: true,
    businessStatus: 'OPERATING',
    tags: [],
  },
  current: null,
  targetDeleted: false,
  photos: [],
  reviewedBy: null,
  reviewedAt: null,
  rejectReason: null,
  approved: null,
  before: null,
})

describe('真实申请查询状态', () => {
  let scope: ReturnType<typeof effectScope>, state: ReturnType<typeof useRestaurantRequests>
  const flush = async () => {
    for (let index = 0; index < 12; index++) await nextTick()
  }
  beforeEach(() => {
    vi.useFakeTimers()
    vi.resetAllMocks()
    setAccessToken('request-reader')
    vi.stubGlobal(
      'URL',
      class extends URL {
        static createObjectURL = vi.fn(() => 'blob:request-photo')
        static revokeObjectURL = vi.fn()
      },
    )
    vi.mocked(fetchRequestPage).mockResolvedValue(listing())
    vi.mocked(fetchRequestDetail).mockImplementation(async (id) => detail(id))
    vi.mocked(fetchRequestPhoto).mockResolvedValue(new Blob(['jpeg']))
    vi.mocked(fetchReviewCategories).mockResolvedValue([
      { code: 'OTHER', name: '其他', enabled: true },
    ])
    scope = effectScope()
  })
  afterEach(() => {
    scope.stop()
    clearAccessToken()
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })
  const start = () => {
    state = scope.run(() => useRestaurantRequests())!
  }

  it('真实列表与详情按独立契约加载，false和零不被当作未知', async () => {
    start()
    await vi.runAllTimersAsync()
    await flush()
    expect(state.items.value.map((entry) => entry.id)).toEqual(['11', '12'])
    expect(state.selected.value?.id).toBe('11')
    expect(state.comparison.value.find((row) => row.label === '堂食情况')?.after).toBe('不支持')
    expect(state.comparison.value.find((row) => row.label === '人均消费')?.after).toBe('¥0')
    expect(fetchRequestPage).toHaveBeenCalledWith(
      expect.objectContaining({ status: 'PENDING', page: 1, pageSize: 20 }),
      'request-reader',
      expect.any(AbortSignal),
    )
  })

  it('列表失败保留错误，不能显示假申请或伪装为空结果', async () => {
    vi.mocked(fetchRequestPage).mockRejectedValue(new Error('读取失败'))
    start()
    await vi.runAllTimersAsync()
    expect(state.listError.value).toBe('读取失败')
    expect(state.items.value).toEqual([])
    expect(state.detail.value).toBeNull()
    vi.mocked(fetchRequestPage).mockResolvedValue(listing([]))
    state.reload()
    await vi.runAllTimersAsync()
    expect(state.listError.value).toBe('')
    expect(state.total.value).toBe(0)
  })

  it('搜索立即清旧详情，迟到旧列表不能覆盖新关键词结果', async () => {
    let resolveOld!: (value: RequestPage) => void
    vi.mocked(fetchRequestPage).mockReturnValueOnce(
      new Promise((resolve) => {
        resolveOld = resolve
      }),
    )
    start()
    await vi.advanceTimersByTimeAsync(0)
    state.query.value = '新关键词'
    await flush()
    vi.mocked(fetchRequestPage).mockResolvedValue(listing([item('99')]))
    await vi.advanceTimersByTimeAsync(250)
    resolveOld(listing())
    await flush()
    expect(state.items.value.map((entry) => entry.id)).toEqual(['99'])
    expect(state.selected.value?.id).toBe('99')
  })

  it('快速切换申请时丢弃旧详情，读取失败可单独重试', async () => {
    let old!: (value: RequestDetail) => void
    vi.mocked(fetchRequestDetail).mockReturnValueOnce(
      new Promise((resolve) => {
        old = resolve
      }),
    )
    start()
    await vi.runAllTimersAsync()
    await state.selectRequest('12')
    old(detail('11'))
    await flush()
    expect(state.detail.value?.application.id).toBe('12')
    vi.mocked(fetchRequestDetail).mockRejectedValueOnce(new Error('详情失败'))
    await state.selectRequest('11')
    expect(state.detail.value).toBeNull()
    expect(state.detailError.value).toBe('详情失败')
    await state.selectRequest('11')
    expect(state.detail.value?.application.id).toBe('11')
  })

  it('图片失败独立重试，切换与卸载清理Blob并取消请求', async () => {
    vi.mocked(fetchRequestDetail).mockImplementation(async (id) => ({
      ...detail(id),
      photos: [{ position: 0, url: '/ignored' }],
    }))
    vi.mocked(fetchRequestPhoto).mockRejectedValueOnce(new Error('云存储不可用'))
    start()
    await vi.runAllTimersAsync()
    expect(state.photos.value[0]?.error).toBe('云存储不可用')
    await state.loadPhoto(0)
    expect(state.photos.value[0]?.src).toBe('blob:request-photo')
    const oldSignal = vi.mocked(fetchRequestPhoto).mock.calls[1]![3]
    await state.selectRequest('12')
    await flush()
    expect(oldSignal.aborted).toBe(true)
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:request-photo')
    scope.stop()
    expect(state.photos.value).toEqual([])
  })

  it('切换状态重置页码并使用服务端计数，不在本地改变申请状态', async () => {
    start()
    await vi.runAllTimersAsync()
    state.tab.value = 'REJECTED'
    await flush()
    await vi.runAllTimersAsync()
    expect(fetchRequestPage).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: 'REJECTED', page: 1 }),
      'request-reader',
      expect.any(AbortSignal),
    )
    expect(state.items.value[0]?.status).toBe('PENDING')
  })

  it('编辑仅发送业务字段，提交失败保留草稿且不推进队列，重试成功后连续加载下一条', async () => {
    start()
    await vi.runAllTimersAsync()
    state.editField('name', '修订名称')
    vi.mocked(approveRequest).mockRejectedValueOnce(new Error('网络中断'))
    await state.decide('approve')
    expect(state.draft.value.name).toBe('修订名称')
    expect(state.selectedId.value).toBe('11')
    expect(state.decisionError.value).toBe('网络中断')
    expect(approveRequest).toHaveBeenCalledWith(
      '11',
      expect.objectContaining({ name: '修订名称', pricePerPerson: null }),
      'request-reader',
    )
    expect(vi.mocked(approveRequest).mock.calls[0]![1]).toMatchObject({
      restaurantType: 'REGULAR',
      supportsDineIn: false,
      supportsDelivery: null,
    })
    vi.mocked(approveRequest).mockResolvedValueOnce({
      id: '11',
      status: 'APPROVED',
      restaurantId: '100',
      reviewedBy: '33',
      reviewedAt: '2026-11-03T07:00:00Z',
      rejectReason: null,
      replayed: true,
      publishedPhotos: [
        { id: '801', position: 0, cover: true, url: '/api/restaurants/100/photos/801' },
      ],
    })
    vi.mocked(fetchRequestPage).mockResolvedValueOnce(listing([item('12')]))
    await state.decide('approve')
    expect(state.selectedId.value).toBe('12')
    expect(state.draft.value.name).toBe('真实申请12')
    expect(state.submitting.value).toBe(false)
    expect(state.notice.value).toContain('已发布 1 张商户相册图片')
  })

  it('写入等待期间禁止双击和切换申请；拒绝成功后未勾选连续审核保留服务端处理详情', async () => {
    start()
    await vi.runAllTimersAsync()
    state.continuous.value = false
    let finish!: (value: Awaited<ReturnType<typeof rejectRequest>>) => void
    vi.mocked(rejectRequest).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    const pending = state.decide('reject', '信息不实')
    await state.selectRequest('12')
    await state.decide('reject', '无效地址')
    expect(state.selectedId.value).toBe('11')
    expect(rejectRequest).toHaveBeenCalledTimes(1)
    vi.mocked(fetchRequestDetail).mockResolvedValueOnce({
      ...detail('11'),
      application: { ...item('11'), status: 'REJECTED' },
      rejectReason: '信息不实',
    })
    vi.mocked(fetchRequestPage).mockResolvedValueOnce(listing([item('12')]))
    finish({
      id: '11',
      status: 'REJECTED',
      restaurantId: null,
      reviewedBy: '33',
      reviewedAt: '2026-11-03T07:00:00Z',
      rejectReason: '信息不实',
      replayed: false,
    })
    await pending
    expect(state.selectedId.value).toBe('11')
    expect(state.selected.value?.status).toBe('REJECTED')
    expect(state.detail.value?.rejectReason).toBe('信息不实')
    expect(state.items.value[0]?.id).toBe('12')
  })

  it('审核成功但队列刷新失败，保留真实终态并显示读取错误，不重发审核', async () => {
    start()
    await vi.runAllTimersAsync()
    vi.mocked(approveRequest).mockResolvedValueOnce({
      id: '11',
      status: 'APPROVED',
      restaurantId: '100',
      reviewedBy: '33',
      reviewedAt: '2026-11-03T07:00:00Z',
      rejectReason: null,
      replayed: false,
    })
    vi.mocked(fetchRequestPage).mockRejectedValueOnce(new Error('列表读取失败'))
    await state.decide('approve')
    await state.decide('approve')
    expect(state.selected.value?.status).toBe('APPROVED')
    expect(state.listError.value).toBe('列表读取失败')
    expect(state.decisionError.value).toBe('')
    expect(approveRequest).toHaveBeenCalledTimes(1)
  })

  it('餐厅类型与三态用餐方式随审核提交，不再只展示却丢弃字段', async () => {
    start()
    await vi.runAllTimersAsync()
    state.editField('restaurantType', 'CANTEEN')
    state.editField('supportsDineIn', true)
    state.editField('supportsDelivery', false)
    vi.mocked(approveRequest).mockRejectedValueOnce(new Error('测试请求截断'))
    await state.decide('approve')
    expect(approveRequest).toHaveBeenLastCalledWith(
      '11',
      expect.objectContaining({
        restaurantType: 'CANTEEN',
        supportsDineIn: true,
        supportsDelivery: false,
      }),
      'request-reader',
    )
    expect(state.comparison.value.find((row) => row.key === 'restaurantType')?.after).toBe('食堂')
    state.editField('supportsDineIn', null)
    await state.decide('approve')
    expect(approveRequest).toHaveBeenLastCalledWith(
      '11',
      expect.objectContaining({ supportsDineIn: null, supportsDelivery: false }),
      'request-reader',
    )
  })

  it('不连续审核时队列缩页也保留当前回执，审核前快照使用服务端值', async () => {
    start()
    await vi.runAllTimersAsync()
    vi.mocked(fetchRequestPage).mockResolvedValueOnce({
      ...listing([item('11')]),
      page: 2,
      totalPages: 2,
      total: 21,
    })
    state.page.value = 2
    await flush()
    await vi.runAllTimersAsync()
    state.continuous.value = false
    vi.mocked(approveRequest).mockResolvedValueOnce({
      id: '11',
      status: 'APPROVED',
      restaurantId: '100',
      reviewedBy: '33',
      reviewedAt: '2026-11-03T07:00:00Z',
      rejectReason: null,
      replayed: false,
    })
    vi.mocked(fetchRequestDetail).mockResolvedValueOnce({
      ...detail('11'),
      application: { ...item('11'), status: 'APPROVED' },
      approved: { ...detail('11').proposed, name: '规范化名称' },
      before: { name: '事务内旧值' },
    })
    vi.mocked(fetchRequestPage)
      .mockResolvedValueOnce({ ...listing([]), total: 1, totalPages: 1, page: 2 })
      .mockResolvedValueOnce(listing([item('12')]))
    await state.decide('approve')
    expect(state.page.value).toBe(1)
    expect(state.selectedId.value).toBe('11')
    expect(state.selected.value?.status).toBe('APPROVED')
    expect(state.comparison.value[0]?.before).toBe('事务内旧值')
    expect(state.comparison.value[0]?.after).toBe('规范化名称')
    expect(state.items.value[0]?.id).toBe('12')
  })

  it('页面卸载后迟到写回执不重新加载其他账号资料', async () => {
    start()
    await vi.runAllTimersAsync()
    let finish!: (value: Awaited<ReturnType<typeof rejectRequest>>) => void
    vi.mocked(rejectRequest).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    const pending = state.decide('reject', '信息不实')
    scope.stop()
    const count = vi.mocked(fetchRequestPage).mock.calls.length
    finish({
      id: '11',
      status: 'REJECTED',
      restaurantId: null,
      reviewedBy: '33',
      reviewedAt: '2026-11-03T07:00:00Z',
      rejectReason: '信息不实',
      replayed: false,
    })
    await pending
    expect(fetchRequestPage).toHaveBeenCalledTimes(count)
    expect(state.detail.value).toBeNull()
  })

  it('校验失败不发送写入，最后一条处理成功后显示空队列', async () => {
    start()
    await vi.runAllTimersAsync()
    state.editField('name', '')
    await state.decide('approve')
    expect(approveRequest).not.toHaveBeenCalled()
    expect(state.decisionError.value).toContain('名称')
    state.editField('name', '有效名称')
    vi.mocked(rejectRequest).mockResolvedValueOnce({
      id: '11',
      status: 'REJECTED',
      restaurantId: null,
      reviewedBy: '33',
      reviewedAt: '2026-11-03T07:00:00Z',
      rejectReason: '图片模糊',
      replayed: false,
    })
    vi.mocked(fetchRequestPage).mockResolvedValueOnce(listing([]))
    await state.decide('reject', '图片模糊')
    expect(state.items.value).toEqual([])
    expect(state.detail.value).toBeNull()
    expect(state.notice.value).toContain('已驳回')
  })
})
