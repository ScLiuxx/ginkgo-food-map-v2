import { createApp, h, nextTick, type App } from 'vue'
import { createPinia, disposePinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useLoginStore } from '@/modules/auth'
import { clearAccessToken } from '@/shared/api/authSession'
import { fetchMyReviews, type MyReviewPage } from '../api/myReviewApi'
import { reviewGatewayKey, unavailableReviewGateway, type ReviewHistoryPage } from '../model/review'
import MyReviews from './MyReviews.vue'

vi.mock('../api/myReviewApi', () => ({ fetchMyReviews: vi.fn() }))
const result = (id = '102', userId = '42', historyCount = 1): MyReviewPage => ({
  author: { id: userId, username: '同学' + userId },
  page: 1,
  pageSize: 8,
  totalGroups: 1,
  totalReviews: historyCount + 1,
  totalPages: 1,
  records: [
    {
      restaurant: { id: '1', name: '<img src=x> 测试店', businessStatus: 'CLOSED' },
      item: {
        id,
        authorId: userId,
        username: '同学' + userId,
        historyCount,
        nextHistoryCursor: historyCount ? 'cursor' : null,
        scoringStatus: 'current',
        overallScore: 8,
        createdAt: '2026-11-07T00:00:00Z',
        scores: { taste: 8, value: 8, service: 8, ambience: 8 },
        recommended: true,
        body: '第' + id + '条真实点评',
        photos: [],
        canDelete: false,
        replies: [],
      },
    },
  ],
})
const empty = (): MyReviewPage => ({
  ...result(),
  records: [],
  totalGroups: 0,
  totalReviews: 0,
  totalPages: 0,
})
const settle = async () => {
  for (let i = 0; i < 14; i++) await nextTick()
}

/** 只替换传输，使用实际点评卡片和历史面板验证确认删除、翻页和会话切换。 */
describe('我的点评页面', () => {
  let app: App | undefined, host: HTMLDivElement
  let pinia: ReturnType<typeof createPinia>
  const remove = vi.fn(),
    history = vi.fn()
  const originalShow = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal')
  const originalClose = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'close')
  beforeEach(() => {
    clearAccessToken()
    vi.resetAllMocks()
    pinia = createPinia()
    setActivePinia(pinia)
    host = document.createElement('div')
    document.body.append(host)
    vi.mocked(fetchMyReviews).mockResolvedValue(result())
    remove.mockResolvedValue(undefined)
    history.mockResolvedValue({
      currentReviewId: '102',
      records: [
        { ...result().records[0]!.item, id: '101', scoringStatus: 'history', body: '历史正文' },
      ],
      hasMore: false,
      nextCursor: null,
    } satisfies ReviewHistoryPage)
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
    for (const [name, descriptor] of [
      ['showModal', originalShow],
      ['close', originalClose],
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
  async function mount(signedIn = true) {
    if (signedIn) signIn()
    const router = createRouter({
      history: createMemoryHistory(),
      routes: ['/', '/account/reviews', '/restaurants/1', '/users/42', '/login'].map((path) => ({
        path,
        component: { template: '<p>目标</p>' },
      })),
    })
    await router.push('/account/reviews')
    await router.isReady()
    app = createApp({ render: () => h(MyReviews) })
      .use(pinia)
      .use(router)
    app.provide(reviewGatewayKey, {
      ...unavailableReviewGateway,
      supportsReplies: true,
      listReplies: vi.fn().mockResolvedValue({ records: [], hasMore: false, nextCursor: null }),
      remove,
      history,
    })
    app.mount(host)
    await settle()
  }
  const buttons = (text: string) =>
    [...host.querySelectorAll<HTMLButtonElement>('button')].filter(
      (button) => button.textContent?.trim() === text,
    )
  const confirm = () => document.querySelector<HTMLDialogElement>('.my-review-confirm')!

  it('显示真实店铺组和历史入口，店名转义，不把暂停营业当删除', async () => {
    await mount()
    expect(host.textContent).toContain('当前可查看 1 家店')
    expect(host.textContent).toContain('暂停营业')
    expect(host.querySelector('.my-review-store a')?.getAttribute('href')).toBe('/restaurants/1')
    expect(host.querySelector('.my-review-store img, .avatar')).toBeNull()
    expect(host.querySelector('.history-trigger')).not.toBeNull()
    expect(buttons('回复')).toHaveLength(1)
    expect(buttons('查看回复')).toHaveLength(1)
    expect(fetchMyReviews).toHaveBeenCalledWith(1, '42', 'session-42', expect.any(AbortSignal))
  })
  it('真实空列表与失败分开，读取失败可重试且不伪造空态', async () => {
    vi.mocked(fetchMyReviews)
      .mockRejectedValueOnce(new Error('列表故障'))
      .mockResolvedValueOnce(empty())
    await mount()
    expect(host.textContent).toContain('列表故障')
    expect(host.querySelector('.my-review-empty')).toBeNull()
    buttons('重试加载')[0]!.click()
    await settle()
    expect(host.textContent).toContain('还没有可展示的点评')
    expect(host.querySelector('.my-review-empty a')?.getAttribute('href')).toBe('/?writeReview=1')
  })
  it('翻页只读目标页，失败重试仍请求目标页', async () => {
    vi.mocked(fetchMyReviews)
      .mockResolvedValueOnce({ ...result(), totalGroups: 9, totalReviews: 10, totalPages: 2 })
      .mockRejectedValueOnce(new Error('第二页失败'))
      .mockResolvedValueOnce({
        ...result('202'),
        page: 2,
        totalGroups: 9,
        totalReviews: 10,
        totalPages: 2,
      })
    await mount()
    buttons('下一页')[0]!.click()
    await settle()
    expect(host.textContent).toContain('第二页失败')
    buttons('重试加载')[0]!.click()
    await settle()
    expect(vi.mocked(fetchMyReviews).mock.calls.map((call) => call[0])).toEqual([1, 2, 2])
    expect(host.textContent).toContain('第202条真实点评')
  })
  it('历史面板复用店铺、账号和游标，历史删除传递精确ID', async () => {
    await mount()
    host.querySelector<HTMLButtonElement>('.history-trigger')!.click()
    await settle()
    expect(history).toHaveBeenCalledWith('1', '42', expect.any(AbortSignal), 'cursor')
    const panel = document.querySelector('.history-dialog')!
    expect(panel.textContent).toContain('历史正文')
    ;[...panel.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.trim() === '删除评价')!
      .click()
    await settle()
    expect(confirm().open).toBe(true)
    confirm().querySelector<HTMLButtonElement>('.confirm-remove')!.click()
    await settle()
    expect(remove).toHaveBeenCalledExactlyOnceWith('101')
  })
  it('必须二次确认，取消不删除；删除成功后回第一页', async () => {
    await mount()
    buttons('删除评价')[0]!.click()
    await settle()
    expect(confirm().textContent).toContain('确定删除这条点评？')
    expect(confirm().querySelector('p')).toBeNull()
    expect(remove).not.toHaveBeenCalled()
    ;[...confirm().querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.trim() === '取消')!
      .click()
    expect(remove).not.toHaveBeenCalled()
    vi.mocked(fetchMyReviews).mockResolvedValueOnce(result('101', '42', 0))
    buttons('删除评价')[0]!.click()
    await settle()
    confirm().querySelector<HTMLButtonElement>('.confirm-remove')!.click()
    await settle()
    expect(remove).toHaveBeenCalledExactlyOnceWith('102')
    expect(vi.mocked(fetchMyReviews).mock.calls.at(-1)?.[0]).toBe(1)
    expect(host.textContent).toContain('第101条真实点评')
  })
  it('删除失败可用原ID重试，成功后读取失败不重新删除', async () => {
    remove.mockRejectedValueOnce(new Error('删除未确认')).mockResolvedValueOnce(undefined)
    await mount()
    buttons('删除评价')[0]!.click()
    await settle()
    confirm().querySelector<HTMLButtonElement>('.confirm-remove')!.click()
    await settle()
    expect(confirm().textContent).toContain('删除未确认')
    vi.mocked(fetchMyReviews)
      .mockRejectedValueOnce(new Error('刷新故障'))
      .mockResolvedValueOnce(empty())
    confirm().querySelector<HTMLButtonElement>('.confirm-remove')!.click()
    await settle()
    expect(remove.mock.calls.map((call) => call[0])).toEqual(['102', '102'])
    expect(host.textContent).toContain('删除已完成，但列表刷新失败')
    buttons('重试加载')[0]!.click()
    await settle()
    expect(remove).toHaveBeenCalledTimes(2)
    expect(host.textContent).toContain('还没有可展示的点评')
  })
  it('换号取消私有查询，晚到旧正文不能覆盖新账号', async () => {
    const pending: { user: string; signal: AbortSignal; resolve: (value: MyReviewPage) => void }[] =
      []
    vi.mocked(fetchMyReviews).mockImplementation(
      (_page, user, _token, signal) =>
        new Promise((resolve) => pending.push({ user, signal, resolve })),
    )
    await mount()
    const old = pending[0]!
    signIn(88)
    await settle()
    expect(old.signal.aborted).toBe(true)
    pending.at(-1)!.resolve(result('888', '88', 0))
    await settle()
    old.resolve(result())
    await settle()
    expect(host.textContent).toContain('第888条真实点评')
    expect(host.textContent).not.toContain('第102条真实点评')
    clearAccessToken()
    await settle()
    expect(host.querySelector('.my-review-group')).toBeNull()
  })
  it('旧删除回执不刷新新账号，提交期间不重复发送', async () => {
    let complete!: () => void
    remove.mockImplementationOnce(() => new Promise<void>((resolve) => (complete = resolve)))
    await mount()
    buttons('删除评价')[0]!.click()
    await settle()
    const submit = confirm().querySelector<HTMLButtonElement>('.confirm-remove')!
    submit.click()
    submit.click()
    await settle()
    expect(remove).toHaveBeenCalledOnce()
    vi.mocked(fetchMyReviews).mockResolvedValue(result('888', '88', 0))
    signIn(88)
    await settle()
    const reads = vi.mocked(fetchMyReviews).mock.calls.length
    complete()
    await settle()
    expect(fetchMyReviews).toHaveBeenCalledTimes(reads)
    expect(host.textContent).toContain('第888条真实点评')
    expect(host.textContent).not.toContain('点评已删除')
  })
  it('游客不查询私有内容', async () => {
    await mount(false)
    expect(fetchMyReviews).not.toHaveBeenCalled()
    expect(host.querySelector('.my-review-group')).toBeNull()
  })
})
