import { createApp, h, nextTick, type App } from 'vue'
import { createPinia, disposePinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useLoginStore } from '@/modules/auth'
import { AxiosError } from 'axios'
import { clearAccessToken } from '@/shared/api/authSession'
import {
  fetchMyRequestPage,
  fetchMyRequestDetail,
  fetchMyRequestPhoto,
  type MyRequestPage,
  type MyRequestDetail,
  type MyRequestItem,
} from '../api/myRestaurantRequestsApi'
import MyContributions from './MyContributions.vue'

vi.mock('../api/myRestaurantRequestsApi', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/myRestaurantRequestsApi')>()),
  fetchMyRequestPage: vi.fn(),
  fetchMyRequestDetail: vi.fn(),
  fetchMyRequestPhoto: vi.fn(),
}))
const item = (id = '101'): MyRequestItem => ({
  id,
  restaurantId: '501',
  requestType: 'UPDATE' as const,
  status: 'REJECTED' as const,
  name: '<img src=x> 纠错店名',
  createdAt: '2026-11-01T12:00:00Z',
  reviewedAt: '2026-11-02T12:00:00Z',
  photoCount: 1,
})
const page = (userId = '42'): MyRequestPage => ({
  userId,
  records: [item()],
  counts: { ALL: 1, PENDING: 0, APPROVED: 0, REJECTED: 1 },
  total: 1,
  totalPages: 1,
  page: 1,
  pageSize: 10,
})
const detail = (id = '101', userId = '42'): MyRequestDetail => ({
  userId,
  application: item(id),
  proposed: { name: '原提交店名', categoryName: '其他美食', restaurantType: 'REGULAR' },
  approved: null,
  rejectReason: '<script>不能执行</script> 请补充地址',
  targetAvailable: true,
  photoPositions: [0],
})
const settle = async () => {
  for (let i = 0; i < 12; i++) await nextTick()
}

/** 用真实身份Store测试列表/详情/私有图片的失败、取消和换号隔离，不执行真实投稿写入。 */
describe('我的投稿与纠错', () => {
  let app: App | undefined, host: HTMLDivElement, pinia: ReturnType<typeof createPinia>
  const show = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal'),
    close = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'close')
  beforeEach(() => {
    clearAccessToken()
    vi.resetAllMocks()
    pinia = createPinia()
    setActivePinia(pinia)
    host = document.createElement('div')
    document.body.append(host)
    vi.mocked(fetchMyRequestPage).mockImplementation(async (_status, _page, userId) => page(userId))
    vi.mocked(fetchMyRequestDetail).mockImplementation(async (id, userId) => detail(id, userId))
    vi.mocked(fetchMyRequestPhoto).mockResolvedValue(new Blob(['jpeg'], { type: 'image/jpeg' }))
    vi.stubGlobal(
      'URL',
      class extends URL {
        static createObjectURL = vi.fn(() => 'blob:private-photo')
        static revokeObjectURL = vi.fn()
      },
    )
    Object.defineProperties(HTMLDialogElement.prototype, {
      showModal: {
        configurable: true,
        value() {
          this.open = true
        },
      },
      close: {
        configurable: true,
        value() {
          if (!this.open) return
          this.open = false
          this.dispatchEvent(new Event('close'))
        },
      },
    })
  })
  afterEach(() => {
    app?.unmount()
    app = undefined
    host.remove()
    disposePinia(pinia)
    clearAccessToken()
    vi.unstubAllGlobals()
    for (const [name, descriptor] of [
      ['showModal', show],
      ['close', close],
    ] as const) {
      if (descriptor) Object.defineProperty(HTMLDialogElement.prototype, name, descriptor)
      else Reflect.deleteProperty(HTMLDialogElement.prototype, name)
    }
  })
  function signIn(id = 42) {
    useLoginStore().acceptSession({
      accessToken: 'session-' + id,
      tokenType: 'Bearer',
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id, username: '同学' + id, roles: ['ROLE_USER'] },
    })
  }
  async function mount(signedIn = true, path = '/account/contributions') {
    if (signedIn) signIn()
    const router = createRouter({
      history: createMemoryHistory(),
      routes: ['/', '/account/contributions', '/restaurants/submit', '/restaurants/501'].map(
        (path) => ({ path, component: { template: '<p>目标</p>' } }),
      ),
    })
    await router.push(path)
    await router.isReady()
    app = createApp({ render: () => h(MyContributions) })
      .use(pinia)
      .use(router)
    app.mount(host)
    await settle()
    return router
  }
  async function open() {
    host.querySelector<HTMLButtonElement>('.contribution-card')!.click()
    await settle()
  }
  function button(text: string) {
    return [...host.querySelectorAll<HTMLButtonElement>('button')].find((node) =>
      node.textContent?.includes(text),
    )!
  }

  it('展示真实记录、计数和状态筛选，店名按纯文本显示', async () => {
    await mount()
    expect(host.querySelectorAll('.contribution-card')).toHaveLength(1)
    expect(host.textContent).toContain('<img src=x> 纠错店名')
    expect(host.querySelector('.contribution-card img')).toBeNull()
    expect(host.querySelector('.contribution-submit')?.getAttribute('href')).toBe(
      '/restaurants/submit',
    )
    button('待审核').click()
    await settle()
    expect(fetchMyRequestPage).toHaveBeenLastCalledWith(
      'PENDING',
      1,
      '42',
      'session-42',
      expect.any(AbortSignal),
    )
    expect(fetchMyRequestDetail).not.toHaveBeenCalled()
  })
  it('列表失败可重试，错误不显示真实空态或伪造计数', async () => {
    vi.mocked(fetchMyRequestPage).mockRejectedValueOnce(new Error('暂时不可用'))
    await mount()
    expect(host.textContent).toContain('暂时不可用')
    expect(host.querySelector('.contribution-empty')).toBeNull()
    expect(host.querySelector('.contributions-filters span')).toBeNull()
    button('重试加载').click()
    await settle()
    expect(host.querySelector('.contribution-card')).not.toBeNull()
  })
  it('真实空列表与筛选空结果分别提示，游客不发私有请求', async () => {
    vi.mocked(fetchMyRequestPage).mockResolvedValue({
      ...page(),
      records: [],
      counts: { ALL: 0, PENDING: 0, APPROVED: 0, REJECTED: 0 },
      total: 0,
      totalPages: 0,
    })
    await mount()
    expect(host.textContent).toContain('还没有投稿或纠错记录')
    button('待审核').click()
    await settle()
    expect(host.textContent).toContain('暂无待审核的申请')
    clearAccessToken()
    await settle()
    vi.mocked(fetchMyRequestPage).mockClear()
    expect(fetchMyRequestPage).not.toHaveBeenCalled()
    expect(host.querySelector('.contribution-empty')).toBeNull()
  })
  it('分页、末页变化与重试使用正确页码，筛选从第一页开始', async () => {
    vi.mocked(fetchMyRequestPage).mockResolvedValueOnce({
      ...page(),
      total: 11,
      totalPages: 2,
      counts: { ALL: 11, PENDING: 0, APPROVED: 0, REJECTED: 11 },
    })
    await mount()
    button('下一页').click()
    await settle()
    expect(fetchMyRequestPage).toHaveBeenLastCalledWith(
      'ALL',
      2,
      '42',
      'session-42',
      expect.any(AbortSignal),
    )
    button('已驳回').click()
    await settle()
    expect(fetchMyRequestPage).toHaveBeenLastCalledWith(
      'REJECTED',
      1,
      '42',
      'session-42',
      expect.any(AbortSignal),
    )
  })
  it('详情保留原提交、驳回原因、附图和店铺入口，关闭释放URL', async () => {
    await mount()
    await open()
    expect(host.querySelector<HTMLDialogElement>('.contribution-dialog')!.open).toBe(true)
    expect(host.textContent).toContain('原提交店名')
    expect(host.querySelector('.contribution-rejection')?.textContent).toContain(
      '<script>不能执行</script>',
    )
    expect(host.querySelector('script')).toBeNull()
    expect(fetchMyRequestPhoto).toHaveBeenCalledExactlyOnceWith(
      '101',
      0,
      'session-42',
      expect.any(AbortSignal),
    )
    expect(host.querySelector('.contribution-detail-footer a')?.getAttribute('href')).toBe(
      '/restaurants/501',
    )
    host.querySelector<HTMLButtonElement>('[aria-label="查看附图 1"]')!.click()
    await settle()
    expect(host.querySelector<HTMLDialogElement>('.contribution-lightbox')!.open).toBe(true)
    host.querySelector<HTMLButtonElement>('[aria-label="关闭申请详情"]')!.click()
    await settle()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:private-photo')
    expect(host.querySelector('.contribution-rejection')).toBeNull()
    expect(host.querySelector<HTMLDialogElement>('.contribution-lightbox')!.open).toBe(false)
  })
  it('审核通过显示最终快照，失效目标不提供链接', async () => {
    const value = detail()
    value.application.status = 'APPROVED'
    value.rejectReason = null
    value.approved = { name: '实际通过店名' }
    value.targetAvailable = false
    value.photoPositions = []
    value.application.photoCount = 0
    vi.mocked(fetchMyRequestDetail).mockResolvedValue(value)
    await mount()
    await open()
    expect(host.textContent).toContain('审核通过的内容')
    expect(host.textContent).toContain('实际通过店名')
    expect(host.textContent).toContain('原提交店名')
    expect(host.querySelector('.contribution-detail-footer a')).toBeNull()
    expect(host.textContent).toContain('关联店铺已失效')
  })
  it('详情失败不触发图片读取，可在同一弹窗重试', async () => {
    vi.mocked(fetchMyRequestDetail).mockRejectedValueOnce(new Error('详情故障'))
    await mount()
    await open()
    expect(host.textContent).toContain('详情故障')
    expect(fetchMyRequestPhoto).not.toHaveBeenCalled()
    button('重试详情').click()
    await settle()
    expect(host.querySelector<HTMLDialogElement>('.contribution-dialog')!.open).toBe(true)
    expect(host.textContent).toContain('原提交店名')
  })
  it('单图失败独立重试，不重复详情查询，解码失败也能重试', async () => {
    vi.mocked(fetchMyRequestPhoto).mockRejectedValueOnce(new Error('图片故障'))
    await mount()
    await open()
    expect(host.textContent).toContain('图片故障')
    button('重试图片 1').click()
    await settle()
    expect(fetchMyRequestDetail).toHaveBeenCalledTimes(1)
    expect(host.querySelector('.contribution-photo img')).not.toBeNull()
    host.querySelector('img')!.dispatchEvent(new Event('error'))
    await settle()
    expect(host.textContent).toContain('图片无法显示')
    button('重试图片 1').click()
    await settle()
    expect(fetchMyRequestPhoto).toHaveBeenCalledTimes(3)
  })
  it('关闭详情后晚到图片不创建Blob，晚到详情也不能恢复内容', async () => {
    let finish: ((blob: Blob) => void) | undefined
    vi.mocked(fetchMyRequestPhoto).mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    await mount()
    await open()
    host.querySelector<HTMLButtonElement>('[aria-label="关闭申请详情"]')!.click()
    await settle()
    finish!(new Blob(['jpeg'], { type: 'image/jpeg' }))
    await settle()
    expect(URL.createObjectURL).not.toHaveBeenCalled()
    let finishDetail: ((value: MyRequestDetail) => void) | undefined
    vi.mocked(fetchMyRequestDetail).mockImplementation(
      () =>
        new Promise((resolve) => {
          finishDetail = resolve
        }),
    )
    await open()
    host.querySelector<HTMLButtonElement>('[aria-label="关闭申请详情"]')!.click()
    await settle()
    finishDetail!(detail())
    await settle()
    expect(host.querySelector('.contribution-detail-summary')).toBeNull()
  })
  it('通知深链自动打开指定申请并保留BIGINT精度；失效目标提示明确且不展示正文', async () => {
    const missing = new AxiosError('missing')
    missing.response = { status: 404 } as NonNullable<AxiosError['response']>
    vi.mocked(fetchMyRequestDetail).mockRejectedValueOnce(missing)
    await mount(true, '/account/contributions?requestId=9007199254740995')
    expect(fetchMyRequestDetail).toHaveBeenCalledWith(
      '9007199254740995',
      '42',
      'session-42',
      expect.any(AbortSignal),
    )
    expect(host.textContent).toContain('内容已失效或不可访问')
    expect(host.querySelector('.contribution-detail-summary')).toBeNull()
    expect(
      host.querySelector<HTMLDialogElement>('.contribution-dialog')?.open ??
        host.querySelector<HTMLDialogElement>('dialog')?.open,
    ).toBe(true)
  })
  it('拒绝非法通知深链，不根据任意地址请求申请', async () => {
    await mount(true, '/account/contributions?requestId=9223372036854775808')
    expect(fetchMyRequestDetail).not.toHaveBeenCalled()
  })
  it('换号取消旧列表，拒绝迟到响应，退出清除私有记录和图片', async () => {
    const pending: { signal: AbortSignal; resolve: (value: MyRequestPage) => void }[] = []
    vi.mocked(fetchMyRequestPage).mockImplementation(
      (_status, _page, _owner, _token, signal) =>
        new Promise((resolve) => pending.push({ signal, resolve })),
    )
    await mount()
    const old = pending[0]!
    signIn(88)
    await settle()
    const current = pending.at(-1)!
    expect(old.signal.aborted).toBe(true)
    const newest = page('88')
    newest.records[0]!.name = '新账号记录'
    current.resolve(newest)
    await settle()
    old.resolve(page())
    await settle()
    expect(host.textContent).toContain('新账号记录')
    expect(host.textContent).not.toContain('纠错店名')
    await open()
    clearAccessToken()
    await settle()
    expect(host.querySelector('.contribution-card,.contribution-detail-summary')).toBeNull()
    expect(URL.revokeObjectURL).toHaveBeenCalled()
  })
})
