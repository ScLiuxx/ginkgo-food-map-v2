import { createApp, nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearAccessToken, setAccessToken } from '@/shared/api/authSession'
import AdminReviewWorkspace from './AdminReviewWorkspace.vue'
import {
  approveRequest,
  fetchRequestDetail,
  fetchRequestPage,
  fetchReviewCategories,
  type RequestDetail,
  type RequestItem,
} from '../api/restaurantRequestsApi'

vi.mock('../api/restaurantRequestsApi', () => ({
  approveRequest: vi.fn(),
  rejectRequest: vi.fn(),
  linkExistingRequest: vi.fn(),
  fetchRequestDetail: vi.fn(),
  fetchRequestPage: vi.fn(),
  fetchReviewCategories: vi.fn(),
  fetchRequestPhoto: vi.fn(),
  searchExistingRestaurants: vi.fn(),
}))

/** 使用真实查询模型和DOM核对资料简化后仍能编辑、提交，并正确区分纠错及已收录记录。 */
describe('审核工作区资料展示与操作', () => {
  let app: ReturnType<typeof createApp> | undefined
  let host: HTMLDivElement
  let record: RequestDetail
  const flush = async () => {
    for (let index = 0; index < 12; index++) await nextTick()
  }
  beforeEach(() => {
    vi.useFakeTimers()
    vi.resetAllMocks()
    // jsdom未实现原生dialog方法；这里只补齐浏览器能力，审核查询和提交仍使用真实模型。
    HTMLDialogElement.prototype.showModal = vi.fn()
    HTMLDialogElement.prototype.close = vi.fn()
    setAccessToken('isolated-admin')
    host = document.createElement('div')
    document.body.append(host)
    const application: RequestItem = {
      id: '11',
      name: '申请店铺',
      requestType: 'CREATE',
      status: 'PENDING',
      restaurantId: null,
      submittedBy: '22',
      submitterName: '测试同学',
      createdAt: '2026-11-17T06:20:00Z',
      photoCount: 0,
    }
    record = {
      application,
      proposed: {
        name: '申请店铺',
        restaurantType: 'REGULAR',
        categoryCode: 'FAST',
        categoryName: '快餐简餐',
        address: '南门 12 号',
        latitude: 30.75,
        longitude: 103.93,
        locationConfirmed: true,
        priceKind: 'EXACT',
        pricePerPerson: 18,
        supportsDineIn: true,
        supportsDelivery: false,
        businessStatus: 'OPERATING',
        tags: [],
      },
      current: null,
      before: null,
      approved: null,
      targetDeleted: false,
      photos: [],
      reviewedBy: null,
      reviewedAt: null,
      rejectReason: null,
    }
    vi.mocked(fetchRequestPage).mockImplementation(async () => ({
      records: [record.application],
      total: 1,
      totalPages: 1,
      page: 1,
      pageSize: 20,
      counts: { PENDING: 1, APPROVED: 0, REJECTED: 0 },
    }))
    vi.mocked(fetchRequestDetail).mockImplementation(async () => structuredClone(record))
    vi.mocked(fetchReviewCategories).mockResolvedValue([
      { code: 'FAST', name: '快餐简餐', enabled: true },
    ])
  })
  afterEach(() => {
    app?.unmount()
    app = undefined
    host.remove()
    clearAccessToken()
    vi.useRealTimers()
  })
  async function mount() {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
    })
    await router.push('/admin/reviews')
    app = createApp(AdminReviewWorkspace, {
      username: '管理员',
      loggingOut: false,
      sessionError: null,
    })
    app.use(router).mount(host)
    await vi.runAllTimersAsync()
    await flush()
  }
  const click = async (selector: string) => {
    host.querySelector<HTMLButtonElement>(selector)!.click()
    await flush()
  }

  it('新店仅显示一列完整资料，编辑后的最后输入直接带入审核请求', async () => {
    let finish!: (value: Awaited<ReturnType<typeof approveRequest>>) => void
    vi.mocked(approveRequest).mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    await mount()
    expect(host.querySelector('.comparison thead')).toBeNull()
    expect(host.querySelectorAll('.comparison tbody tr')).toHaveLength(11)
    expect(host.querySelectorAll('.comparison tbody tr:first-child > *')).toHaveLength(2)
    await click('.single-column .editable-value')
    const input = host.querySelector<HTMLInputElement>('input[aria-label="name"]')!
    input.value = '核对后的店名'
    input.dispatchEvent(new Event('input'))
    await flush()
    await click('.continuous-toggle input')
    await click('.btn.primary')
    expect(approveRequest).toHaveBeenCalledWith(
      '11',
      expect.objectContaining({
        name: '核对后的店名',
        latitude: 30.75,
        longitude: 103.93,
        supportsDelivery: false,
      }),
      'isolated-admin',
    )
    expect(host.querySelector<HTMLButtonElement>('.btn.primary')!.disabled).toBe(true)
    record.application.status = 'APPROVED'
    record.approved = { ...record.proposed, name: '核对后的店名' }
    finish({
      id: '11',
      status: 'APPROVED',
      restaurantId: '88',
      reviewedBy: '1',
      reviewedAt: '2026-11-17T07:00:00Z',
      rejectReason: null,
      replayed: false,
    })
    await flush()
    expect(host.querySelector('.status-badge')?.textContent).toBe('已通过')
    expect(host.querySelector('.btn.primary')).toBeNull()
    expect(host.querySelector('.detail-content')?.textContent).toContain('核对后的店名')
  })

  it('资料纠错保留当前值、提交值和修改标记', async () => {
    record.application.requestType = 'UPDATE'
    record.application.restaurantId = '88'
    record.current = { ...record.proposed, address: '旧地址' }
    await mount()
    expect(host.querySelector('.comparison thead')?.textContent).toContain('当前商户资料')
    const row = Array.from(host.querySelectorAll('tbody tr')).find((entry) =>
      entry.textContent?.includes('详细地址'),
    )!
    expect(row.querySelector('.old-value')?.textContent).toContain('旧地址')
    expect(row.querySelector('.changed')?.textContent).toContain('南门 12 号')
    expect(host.querySelector('.btn.secondary')).toBeNull()
  })

  it('切换申请销毁旧编辑器，Escape不能把上一家店名写入当前申请', async () => {
    vi.mocked(fetchRequestPage).mockResolvedValue({
      records: [record.application, { ...record.application, id: '12', name: '第二家店铺' }],
      total: 2,
      totalPages: 1,
      page: 1,
      pageSize: 20,
      counts: { PENDING: 2, APPROVED: 0, REJECTED: 0 },
    })
    vi.mocked(fetchRequestDetail).mockImplementation(async (id) => ({
      ...structuredClone(record),
      application: { ...record.application, id, name: id === '12' ? '第二家店铺' : '申请店铺' },
      proposed: { ...record.proposed, name: id === '12' ? '第二家店铺' : '申请店铺' },
    }))
    await mount()
    await click('.single-column .editable-value')
    await click('.request:nth-child(2)')
    expect(host.querySelector('input[aria-label="name"]')).toBeNull()
    await click('.single-column .editable-value')
    const input = host.querySelector<HTMLInputElement>('input[aria-label="name"]')!
    input.value = '临时修改'
    input.dispatchEvent(new Event('input'))
    await flush()
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flush()
    expect(host.querySelector('tbody tr')?.textContent).toContain('第二家店铺')
    expect(host.querySelector('tbody tr')?.textContent).not.toContain('申请店铺')
  })

  it('已收录历史保留原投稿与关联资料对照，不提供再次审核入口', async () => {
    record.application.status = 'APPROVED'
    record.application.approvalMode = 'LINKED_EXISTING'
    record.application.restaurantId = '88'
    record.approved = { ...record.proposed, name: '已有店铺', address: '已有地址' }
    await mount()
    expect(host.querySelector('.status-badge')?.textContent).toBe('店铺已收录')
    expect(host.querySelector('.comparison thead')?.textContent).toContain('关联店铺资料（处理时）')
    expect(host.querySelector('tbody tr')?.textContent).toContain('申请店铺')
    expect(host.querySelector('tbody tr')?.textContent).toContain('已有店铺')
    expect(host.querySelector('.decision-controls')).toBeNull()
    expect(host.querySelector('.editable-value')).toBeNull()
  })

  it('读取失败时可重试详情，失败期间不能通过空资料', async () => {
    vi.mocked(fetchRequestDetail).mockRejectedValueOnce(new Error('读取失败'))
    await mount()
    expect(host.querySelector('.query-error')?.textContent).toContain('读取失败')
    expect(host.querySelector<HTMLButtonElement>('.btn.primary')!.disabled).toBe(true)
    await click('.query-error button')
    expect(host.querySelector('.query-error')).toBeNull()
    expect(host.querySelectorAll('.single-column tbody tr')).toHaveLength(11)
    expect(approveRequest).not.toHaveBeenCalled()
  })
})
