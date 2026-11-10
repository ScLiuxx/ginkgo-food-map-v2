import { createApp, h, nextTick, type App } from 'vue'
import { createPinia, disposePinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { useLoginStore } from '@/modules/auth'
import { clearAccessToken } from '@/shared/api/authSession'
import {
  fetchNotifications,
  markNotificationRead,
  markAllNotificationsRead,
  deleteNotification,
  type NotificationItem,
  type NotificationPage,
} from '@/modules/message/api/notificationsApi'
import MessagePage from './MessagePage.vue'
import { fetchReplyTarget, type ReplyTarget } from '@/modules/review/api/replyTargetApi'
import { AxiosError } from 'axios'
import { httpClient } from '@/shared/api/httpClient'
vi.mock('@/modules/review/api/replyTargetApi', () => ({ fetchReplyTarget: vi.fn() }))

const replyPage = (): NotificationPage => ({
  userId: '42',
  category: 'COMMENTS',
  page: 1,
  pageSize: 10,
  total: 1,
  totalPages: 1,
  unreadCount: 1,
  unreadCounts: { SYSTEM: 3, COMMENTS: 1 },
  latestSystem: null,
  records: [
    {
      id: '501',
      sourceId: '9007199254740997',
      reviewId: '301',
      actorUserId: '88',
      actorUsername: '回复同学',
      category: 'COMMENTS',
      requestId: null,
      requestType: null,
      decision: null,
      restaurantName: null,
      rejectReason: null,
      createdAt: '2026-11-07T08:00:00Z',
      readAt: null,
    },
  ],
})
const target = (): ReplyTarget => ({
  userId: '42',
  restaurantId: '1',
  reviewId: '301',
  replyId: '9007199254740997',
  reviewAuthor: { id: '42', username: '点评同学' },
  replyAuthor: { id: '88', username: '回复同学' },
  review: {
    id: '301',
    body: '历史点评原文',
    createdAt: '2026-11-01T00:00:00Z',
    overallScore: 8,
    recommended: true,
  },
  replyBody: '这条回复在第十二页 <script>纯文本</script>',
  replyCreatedAt: '2026-11-07T08:00:00Z',
})

vi.mock('@/modules/message/api/notificationsApi', () => ({
  notificationId: (value: unknown) => typeof value === 'string' && /^[1-9]\d*$/.test(value),
  fetchNotifications: vi.fn(),
  markNotificationRead: vi.fn(),
  markAllNotificationsRead: vi.fn(),
  deleteNotification: vi.fn(),
}))
const page = (
  userId = '42',
  category: 'ALL' | 'SYSTEM' | 'COMMENTS' | 'LIKES' | 'FOLLOWERS' = 'SYSTEM',
): NotificationPage => ({
  userId,
  category,
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
      restaurantName: '<img src=x> 店名',
      rejectReason: '<script>原因</script>',
      createdAt: '2026-11-07T08:00:00Z',
      readAt: null,
    },
  ],
})
const settle = async () => {
  for (let i = 0; i < 20; i++) await nextTick()
}
/** 使用真实认证Store和路由，验证消息写入、失败保留及跨账号迟到结果隔离。 */
describe('审核通知页面', () => {
  let app: App | undefined, host: HTMLDivElement, pinia: ReturnType<typeof createPinia>
  beforeEach(() => {
    clearAccessToken()
    vi.resetAllMocks()
    pinia = createPinia()
    setActivePinia(pinia)
    host = document.createElement('div')
    document.body.append(host)
    vi.mocked(fetchNotifications).mockImplementation(async (category, _page, userId) => {
      const value = page(userId, category)
      value.latestSystem = value.records[0]!
      return value
    })
    vi.mocked(markNotificationRead).mockResolvedValue(undefined)
    vi.mocked(markAllNotificationsRead).mockResolvedValue(undefined)
    vi.mocked(deleteNotification).mockResolvedValue(undefined)
    vi.mocked(fetchReplyTarget).mockResolvedValue(target())
    Object.defineProperty(HTMLDialogElement.prototype, 'close', {
      configurable: true,
      value() {
        this.open = false
      },
    })
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
      configurable: true,
      value() {
        this.open = true
      },
    })
  })
  afterEach(() => {
    vi.restoreAllMocks()
    app?.unmount()
    app = undefined
    disposePinia(pinia)
    host.remove()
    clearAccessToken()
    delete (HTMLDialogElement.prototype as unknown as Record<string, unknown>).showModal
    delete (HTMLDialogElement.prototype as unknown as Record<string, unknown>).close
  })
  function signIn(id = 42) {
    useLoginStore().acceptSession({
      accessToken: 'session-' + id,
      tokenType: 'Bearer',
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id, username: '同学' + id, roles: ['ROLE_USER'] },
    })
  }
  async function mount(path = '/messages/system', signedIn = true) {
    if (signedIn) signIn()
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: '/messages/:category(likes|comments|followers|system)?',
          component: MessagePage,
          props: (route) => ({ category: route.params.category || undefined }),
        },
        { path: '/account/contributions', component: { template: '<p>本人申请</p>' } },
        { path: '/users/:id', component: { template: '<p>公开主页</p>' } },
        { path: '/login', component: { template: '<p>登录</p>' } },
        { path: '/', component: { template: '<p>首页</p>' } },
      ],
    })
    await router.push(path)
    await router.isReady()
    app = createApp({ render: () => h(RouterView) })
      .use(pinia)
      .use(router)
    app.mount(host)
    await settle()
    return router
  }

  async function click(label: string) {
    const button = [...host.querySelectorAll('button')].find(
      (value) => value.textContent?.trim() === label,
    )
    expect(button, label).toBeDefined()
    button!.click()
    await settle()
  }

  it('删除需要二次确认，取消不写入，成功后回到第一页读取真实计数', async () => {
    vi.mocked(fetchNotifications).mockImplementation(async (category, current, userId) => ({
      ...page(userId, category),
      page: current,
      total: 11,
      totalPages: 2,
    }))
    await mount()
    await click('下一页')
    await click('删除消息')
    expect(deleteNotification).not.toHaveBeenCalled()
    expect(markNotificationRead).not.toHaveBeenCalled()
    await click('取消删除')
    expect(deleteNotification).not.toHaveBeenCalled()
    await click('删除消息')
    vi.mocked(fetchNotifications).mockImplementationOnce(async (category, current, userId) => ({
      ...page(userId, category),
      page: current,
      records: [],
      total: 0,
      totalPages: 0,
      unreadCount: 0,
      unreadCounts: { SYSTEM: 0, COMMENTS: 0 },
      latestSystem: null,
    }))
    await click('确认删除消息')
    expect(deleteNotification).toHaveBeenCalledWith(
      '9007199254740993',
      'session-42',
      expect.any(AbortSignal),
    )
    expect(vi.mocked(fetchNotifications).mock.calls.at(-1)?.[1]).toBe(1)
    expect(host.textContent).toContain('消息已删除')
    expect(host.textContent).toContain('0 条未读')
    expect(host.textContent).toContain('暂无系统通知')
    expect(host.querySelector('.notification-card')).toBeNull()
    expect(markNotificationRead).not.toHaveBeenCalled()
  })

  it('删除失败保留原通知和确认入口，可安全重试已读消息', async () => {
    const data = page()
    data.records[0]!.readAt = '2026-11-07T09:00:00Z'
    data.unreadCount = 0
    vi.mocked(fetchNotifications).mockResolvedValue(data)
    await mount()
    vi.mocked(deleteNotification).mockRejectedValueOnce(new Error('暂时不可用'))
    await click('删除消息')
    await click('确认删除消息')
    expect(host.textContent).toContain('删除未确认')
    expect(host.querySelector('.notification-card')).not.toBeNull()
    expect(host.querySelector('[aria-label="确认删除消息"]')).not.toBeNull()
    await click('确认删除消息')
    expect(deleteNotification).toHaveBeenCalledTimes(2)
  })

  it('删除成功但读取失败时隐藏旧消息，重试读取不重发DELETE', async () => {
    await mount()
    await click('删除消息')
    vi.mocked(fetchNotifications).mockRejectedValueOnce(new Error('读取失败'))
    await click('确认删除消息')
    expect(host.textContent).toContain('消息已删除')
    expect(host.textContent).toContain('读取失败')
    expect(host.querySelector('.notification-card')).toBeNull()
    await click('重试加载')
    expect(deleteNotification).toHaveBeenCalledTimes(1)
  })

  it('换号取消在途删除并丢弃旧身份回执', async () => {
    await mount()
    let resolveDelete!: () => void
    vi.mocked(deleteNotification).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveDelete = resolve
        }),
    )
    await click('删除消息')
    await click('确认删除消息')
    const signal = vi.mocked(deleteNotification).mock.calls[0]![2]
    signIn(43)
    await settle()
    expect(signal.aborted).toBe(true)
    const reads = vi.mocked(fetchNotifications).mock.calls.length
    resolveDelete()
    await settle()
    expect(fetchNotifications).toHaveBeenCalledTimes(reads)
    expect(host.textContent).not.toContain('消息已删除')
    expect(host.querySelector('[aria-label="确认删除消息"]')).toBeNull()
  })
  it('游客不读取私有消息，登录后新增关注分类读取真实空状态', async () => {
    const router = await mount('/messages/system', false)
    expect(fetchNotifications).not.toHaveBeenCalled()
    expect(host.textContent).toContain('登录账号')
    vi.mocked(fetchNotifications).mockImplementation(async (category, _page, userId) => ({
      ...page(userId, category),
      records: [],
      total: 0,
      totalPages: 0,
      unreadCount: 0,
      unreadCounts: { SYSTEM: 0, COMMENTS: 0, FOLLOWERS: 0 },
      latestSystem: null,
    }))
    signIn()
    await router.push('/messages/followers')
    await settle()
    expect(fetchNotifications).toHaveBeenLastCalledWith(
      'FOLLOWERS',
      1,
      '42',
      'session-42',
      expect.any(AbortSignal),
    )
    expect(host.textContent).toContain('暂无新增关注提醒')
    expect(host.querySelector('.notification-card')).toBeNull()
  })
  it('关注提醒先保存已读，再进入关注者主页', async () => {
    const source: NotificationPage = {
      ...replyPage(),
      category: 'FOLLOWERS',
      unreadCounts: { SYSTEM: 0, COMMENTS: 0, FOLLOWERS: 1 },
      records: [
        {
          ...replyPage().records[0]!,
          category: 'FOLLOWERS',
          notificationType: 'USER_FOLLOWED',
          reviewId: null,
        },
      ],
    }
    vi.mocked(fetchNotifications).mockResolvedValue(source)
    const router = await mount('/messages/followers')
    expect(host.textContent).toContain('回复同学 关注了你')
    host.querySelector<HTMLButtonElement>('.notification-card')!.click()
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/users/88'))
    expect(markNotificationRead).toHaveBeenCalledWith('501', 'session-42', expect.any(AbortSignal))
  })
  it('获赞分类逐条展示，先保存单条已读再鉴权读取回复作者自己的内容', async () => {
    const source = replyPage()
    source.category = 'LIKES'
    source.unreadCounts = { SYSTEM: 3, COMMENTS: 1, LIKES: 1 }
    source.records[0]!.category = 'LIKES'
    source.records[0]!.notificationType = 'REPLY_LIKED'
    vi.mocked(fetchNotifications).mockResolvedValue(source)
    const read = vi.spyOn(httpClient, 'get').mockResolvedValue({
      data: {
        success: true,
        data: {
          userId: '42',
          likeId: source.records[0]!.sourceId,
          targetType: 'REPLY',
          targetId: '901',
          reviewId: '301',
          restaurantId: '1',
          author: { id: '42', username: '回复作者' },
          body: '被赞的历史回复 <script>纯文本</script>',
          createdAt: '2026-11-07T00:00:00Z',
        },
      },
    })
    await mount('/messages/likes')
    expect(host.textContent).toContain('回复同学 赞了你的回复')
    expect(read).not.toHaveBeenCalled()
    host.querySelector<HTMLButtonElement>('.notification-card')!.click()
    await settle()
    expect(markNotificationRead).toHaveBeenCalledWith(
      source.records[0]!.id,
      'session-42',
      expect.any(AbortSignal),
    )
    expect(document.querySelector('.like-target-dialog')?.textContent).toContain(
      '被赞的历史回复 <script>纯文本</script>',
    )
    expect(document.querySelector('.like-target-dialog script')).toBeNull()
    ;(document.querySelector('.like-target-dialog button') as HTMLButtonElement).click()
    await settle()
    expect(document.querySelector('.like-target-dialog')).toBeNull()
    host.querySelector<HTMLButtonElement>('.mark-all-read')!.click()
    await settle()
    expect(markAllNotificationsRead).toHaveBeenCalledWith(
      'LIKES',
      'session-42',
      expect.any(AbortSignal),
    )
  })
  it('获赞目标失效只显示错误，换号后拒绝旧目标响应', async () => {
    const source = replyPage()
    source.category = 'LIKES'
    source.records[0]!.category = 'LIKES'
    source.records[0]!.notificationType = 'REVIEW_LIKED'
    vi.mocked(fetchNotifications).mockResolvedValue(source)
    const read = vi
      .spyOn(httpClient, 'get')
      .mockRejectedValue(
        new AxiosError('gone', undefined, undefined, undefined, { status: 404 } as never),
      )
    await mount('/messages/likes')
    host.querySelector<HTMLButtonElement>('.notification-card')!.click()
    await settle()
    expect(document.querySelector('.like-target-dialog')?.textContent).toContain(
      '内容已失效或不可访问',
    )
    let resolve!: (value: unknown) => void
    read.mockReturnValue(
      new Promise((done) => {
        resolve = done
      }),
    )
    ;(
      document.querySelector('.like-target-dialog [role="alert"] button') as HTMLButtonElement
    ).click()
    await settle()
    signIn(88)
    await settle()
    resolve({ data: { success: true, data: { userId: '42', body: '旧账号秘密' } } })
    await settle()
    expect(document.body.textContent).not.toContain('旧账号秘密')
    expect(document.querySelector('.like-target-dialog')).toBeNull()
  })
  it('列表展示真实未读和纯文本原因，浏览不发送已读；点击成功后跳到精确申请', async () => {
    const router = await mount()
    expect(host.textContent).toContain('1 条未读')
    expect(host.textContent).toContain('<script>原因</script>')
    expect(host.querySelector('img,script')).toBeNull()
    expect(markNotificationRead).not.toHaveBeenCalled()
    expect(markAllNotificationsRead).not.toHaveBeenCalled()
    host.querySelector<HTMLButtonElement>('.notification-card')!.click()
    await settle()
    expect(markNotificationRead).toHaveBeenCalledWith(
      '9007199254740993',
      'session-42',
      expect.any(AbortSignal),
    )
    await vi.waitFor(() =>
      expect(router.currentRoute.value.fullPath).toBe(
        '/account/contributions?requestId=9007199254740995',
      ),
    )
  })
  it('已读失败保留通知和未读数，不跳转也不自动重试', async () => {
    vi.mocked(markNotificationRead).mockRejectedValue(new Error('暂时无法保存已读'))
    const router = await mount()
    host.querySelector<HTMLButtonElement>('.notification-card')!.click()
    await settle()
    expect(host.textContent).toContain('暂时无法保存已读')
    expect(host.textContent).toContain('1 条未读')
    expect(router.currentRoute.value.path).toBe('/messages/system')
    expect(markNotificationRead).toHaveBeenCalledTimes(1)
  })
  it.each([
    ['/messages', 'ALL'],
    ['/messages/system', 'SYSTEM'],
  ] as const)('全部已读使用%s的正确范围', async (path, category) => {
    await mount(path)
    host.querySelector<HTMLButtonElement>('.mark-all-read')!.click()
    await settle()
    expect(markAllNotificationsRead).toHaveBeenCalledWith(
      category,
      'session-42',
      expect.any(AbortSignal),
    )
    expect(fetchNotifications).toHaveBeenCalledTimes(2)
  })
  it('网络故障显示重试，不伪装成暂无系统通知；重试后呈现真实空态', async () => {
    vi.mocked(fetchNotifications).mockRejectedValueOnce(new Error('读取失败'))
    await mount()
    expect(host.textContent).toContain('读取失败')
    expect(host.textContent).not.toContain('暂无系统通知')
    vi.mocked(fetchNotifications).mockResolvedValue({
      ...page(),
      records: [],
      total: 0,
      totalPages: 0,
      unreadCount: 0,
    })
    ;[...host.querySelectorAll('button')].find((b) => b.textContent === '重试加载')!.click()
    await settle()
    expect(host.textContent).toContain('暂无系统通知')
    expect(host.querySelector<HTMLButtonElement>('.mark-all-read')!.disabled).toBe(true)
  })
  it('换号立即清除旧内容，旧读取与写入回执不能覆盖新身份或触发跳转', async () => {
    let finishWrite!: () => void
    vi.mocked(markNotificationRead).mockImplementation(
      () =>
        new Promise((resolve) => {
          finishWrite = resolve
        }),
    )
    const router = await mount()
    host.querySelector<HTMLButtonElement>('.notification-card')!.click()
    await settle()
    let finishRead!: (value: NotificationPage) => void
    vi.mocked(fetchNotifications).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishRead = resolve
        }),
    )
    signIn(99)
    await settle()
    expect(host.textContent).not.toContain('<img src=x> 店名')
    clearAccessToken()
    await settle()
    finishRead(page('99'))
    finishWrite()
    await settle()
    expect(host.querySelector('.notification-card')).toBeNull()
    expect(router.currentRoute.value.path).toBe('/messages/system')
    expect(host.textContent).toContain('登录账号')
  })
  it('首页展示真实摘要及未读数，不再展示即将开放标记', async () => {
    await mount('/messages')
    expect(host.querySelector('.session-unread')?.textContent).toBe('1')
    expect(host.querySelector('.session-preview')?.textContent).toContain('店名')
    expect(host.querySelector('.availability')).toBeNull()
  })
  it('评论分类展示回复者且不显示正文，点击先保存已读再精确读取原点评和目标回复', async () => {
    vi.mocked(fetchNotifications).mockResolvedValue(replyPage())
    await mount('/messages/comments')
    expect(host.textContent).toContain('回复同学 回复了你的点评')
    expect(host.textContent).not.toContain('第十二页')
    expect(fetchReplyTarget).not.toHaveBeenCalled()
    host.querySelector<HTMLButtonElement>('.notification-card')!.click()
    await settle()
    expect(markNotificationRead).toHaveBeenCalledWith('501', 'session-42', expect.any(AbortSignal))
    expect(fetchReplyTarget).toHaveBeenCalledWith(
      '9007199254740997',
      '301',
      '42',
      'session-42',
      expect.any(AbortSignal),
    )
    expect(document.querySelector('.reply-target-review')?.textContent).toContain('历史点评原文')
    expect(document.querySelector('.reply-target-highlight')?.textContent).toContain(
      '第十二页 <script>纯文本</script>',
    )
    expect(document.querySelector('.reply-notification-dialog script')).toBeNull()
    document.querySelector<HTMLButtonElement>('[aria-label="关闭评论详情"]')!.click()
    await settle()
    expect(document.querySelector('.reply-notification-dialog')).toBeNull()
  })
  it('评论分类全部已读只提交COMMENTS，首页分别显示系统与评论未读', async () => {
    const data = replyPage()
    data.category = 'ALL'
    data.unreadCount = 4
    vi.mocked(fetchNotifications).mockResolvedValue(data)
    const router = await mount('/messages')
    expect(host.querySelector('.session-unread')?.textContent).toBe('3')
    expect(host.querySelector('.shortcut-unread')?.textContent).toBe('1')
    expect(host.querySelector('.session-preview')).toBeNull()
    data.category = 'COMMENTS'
    data.unreadCount = 1
    await router.push('/messages/comments')
    await settle()
    host.querySelector<HTMLButtonElement>('.mark-all-read')!.click()
    await settle()
    expect(markAllNotificationsRead).toHaveBeenCalledWith(
      'COMMENTS',
      'session-42',
      expect.any(AbortSignal),
    )
  })
  it('目标已删除时只提示失效，通知仍按单条已读，读取故障可在弹窗重试', async () => {
    vi.mocked(fetchNotifications).mockResolvedValue(replyPage())
    const missing = new AxiosError('missing')
    missing.response = { status: 404 } as NonNullable<AxiosError['response']>
    vi.mocked(fetchReplyTarget).mockRejectedValueOnce(missing)
    await mount('/messages/comments')
    host.querySelector<HTMLButtonElement>('.notification-card')!.click()
    await settle()
    expect(document.querySelector('.reply-notification-dialog')?.textContent).toContain(
      '内容已失效或不可访问',
    )
    expect(document.querySelector('.reply-target-review')).toBeNull()
    expect(markNotificationRead).toHaveBeenCalledTimes(1)
    const buttons = [
      ...document.querySelectorAll<HTMLButtonElement>('.reply-notification-dialog button'),
    ]
    buttons.find((button) => button.textContent === '重试加载')!.click()
    await settle()
    expect(document.querySelector('.reply-target-highlight')?.textContent).toContain('第十二页')
  })
  it('关闭或换号后目标读取迟到，不重新打开弹窗或展示旧正文', async () => {
    vi.mocked(fetchNotifications).mockResolvedValue(replyPage())
    let finish!: (value: ReplyTarget) => void
    vi.mocked(fetchReplyTarget).mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    await mount('/messages/comments')
    host.querySelector<HTMLButtonElement>('.notification-card')!.click()
    await settle()
    const signal = vi.mocked(fetchReplyTarget).mock.calls[0]![4]
    clearAccessToken()
    await settle()
    expect(signal.aborted).toBe(true)
    finish(target())
    await settle()
    expect(document.querySelector('.reply-notification-dialog')).toBeNull()
    expect(document.body.textContent).not.toContain('历史点评原文')
  })
  it.each([
    ['REMOVED', '举报已处理：内容已删除', '被永久删除'],
    ['DISMISSED', '举报未认定违规', '未认定该内容违规'],
  ] as const)('举报结果%s展示摘要，点击只标记已读并展示说明', async (decision, summary, detail) => {
    const source = page()
    const report: NotificationItem = {
      ...source.records[0]!,
      id: '7001',
      sourceId: '55',
      reviewId: '301',
      notificationType: 'REPORT_RESULT',
      requestId: null,
      requestType: null,
      decision,
      restaurantName: null,
      rejectReason: null,
    }
    source.records = [report]
    source.latestSystem = report
    vi.mocked(fetchNotifications).mockResolvedValue(source)
    const router = await mount('/messages')
    expect(host.querySelector('.session-preview')?.textContent).toBe(summary)

    await router.push('/messages/system')
    await settle()
    host.querySelector<HTMLButtonElement>('.notification-card')!.click()
    await settle()
    expect(markNotificationRead).toHaveBeenCalledWith('7001', 'session-42', expect.any(AbortSignal))
    expect(router.currentRoute.value.path).toBe('/messages/system')
    expect(document.querySelector('.report-result-dialog')?.textContent).toContain(detail)
    expect(document.querySelector('.report-result-dialog')?.textContent).toContain(summary)
    ;[...document.querySelectorAll<HTMLButtonElement>('.report-result-dialog button')]
      .find((button) => button.textContent === '关闭')!
      .click()
    await settle()
    expect(document.querySelector('.report-result-dialog')).toBeNull()
  })
  it('公告标题与正文按纯文本展示，已读使用带类型ID而不跳转申请', async () => {
    const source = page()
    const announcement = {
      ...source.records[0]!,
      id: 'announcement:12',
      sourceId: '12',
      notificationType: 'ANNOUNCEMENT',
      announcementId: '12',
      announcementTitle: '校园公告',
      requestId: null,
      requestType: null,
      decision: null,
      restaurantName: null,
      rejectReason: null,
    }
    source.records = [announcement]
    source.latestSystem = announcement
    vi.mocked(fetchNotifications).mockResolvedValue(source)
    vi.spyOn(httpClient, 'get').mockResolvedValue({
      data: {
        success: true,
        data: {
          id: '12',
          title: '校园公告',
          content: '正文 <img src=x onerror=alert(1)>',
          status: 'PUBLISHED',
          publishedBy: '22',
          publishedAt: '2026-11-07T08:00:00Z',
          withdrawnBy: null,
          withdrawnAt: null,
          replayed: false,
        },
      },
    })
    const router = await mount()
    host.querySelector<HTMLButtonElement>('.notification-card')!.click()
    await settle()
    expect(markNotificationRead).toHaveBeenCalledWith(
      'announcement:12',
      'session-42',
      expect.any(AbortSignal),
    )
    expect(router.currentRoute.value.path).toBe('/messages/system')
    expect(document.querySelector('.reader-content')?.textContent).toContain('<img src=x')
    expect(document.querySelector('.reader-content img')).toBeNull()
    clearAccessToken()
    await settle()
    expect(document.querySelector('.announcement-reader')).toBeNull()
  })
})
