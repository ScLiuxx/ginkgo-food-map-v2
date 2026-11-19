import { captureLinkTarget } from '@/shared/test/captureLinkTarget'
import { createApp, h, nextTick, reactive, type App, type VNode } from 'vue'
import { createMemoryHistory, createRouter, RouterView, type Router } from 'vue-router'
import { afterEach, describe, expect, it } from 'vitest'
import MessageCenter from './MessageCenter.vue'
import type { MessageSession } from '../model/messageCategories'
import type { NotificationItem, NotificationPage } from '../api/notificationsApi'

/** 分类导航和登录回跳可用；真实收件箱接通前不把占位状态当成已读取的用户数据。 */
describe('消息中心前端', () => {
  let app: App
  let host: HTMLDivElement

  afterEach(() => {
    app?.unmount()
    host?.remove()
  })

  /** 两类测试共用挂载入口，保持真实路由与受控收件箱使用相同组件生命周期。 */
  function renderPage(render: () => VNode, router: Router) {
    host = document.createElement('div')
    document.body.append(host)
    app = createApp({ render }).use(router)
    app.mount(host)
  }

  async function mount(
    path = '/messages',
    authenticated = false,
    sessions?: readonly MessageSession[],
  ) {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: '/messages/:category(likes|comments|followers|system)?',
          component: MessageCenter,
          props: (route) => ({
            category: route.params.category || undefined,
            authenticated,
            sessions,
          }),
        },
        { path: '/messages/reviews', redirect: '/messages/system' },
        { path: '/messages/private', redirect: '/messages' },
        { path: '/', component: { template: '<p>发现美食</p>' } },
        { path: '/login', component: { template: '<p>账号登录</p>' } },
      ],
    })
    await router.push(path)
    await router.isReady()
    renderPage(() => h(RouterView), router)
    return router
  }

  it('首页保留三个互动入口，系统通知为唯一会话，不显示私信、审核机器人或额外空态', async () => {
    await mount()
    expect(host.querySelectorAll('.message-shortcut')).toHaveLength(3)
    expect(host.querySelectorAll('.session-row')).toHaveLength(1)
    expect(host.querySelector('.session-row')?.textContent).toContain('系统通知')
    expect(host.querySelector('.session-row')?.textContent?.trim()).toBe('系统通知')
    expect(host.querySelector('.shortcut-description, .session-summary')).toBeNull()
    expect(host.querySelector('.session-empty')).toBeNull()
    expect(host.querySelector('h1')?.textContent).toBe('消息')
    expect(host.textContent).toContain('即将开放')
    expect(host.textContent).not.toMatch(/私信|审核通知|小管家|消息\(\d|暂无未读|\d{2}:\d{2}/)
    expect(
      [...host.querySelectorAll('.message-shortcut strong')].map((item) => item.textContent),
    ).toEqual(['收到的赞', '评论', '新增关注'])
    expect(host.querySelector<HTMLButtonElement>('.mark-all-read')?.disabled).toBe(true)
    expect(host.querySelector('.session-time, .session-unread')).toBeNull()
  })

  it('点击分类进入独立页面，路由切换后更新内容，返回中心恢复入口', async () => {
    const router = await mount()
    host.querySelector<HTMLAnchorElement>('.session-row')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await nextTick()
    expect(router.currentRoute.value.path).toBe('/messages/system')
    expect(host.querySelector('h1')?.textContent).toBe('系统通知')
    expect(host.textContent).toContain('驳回原因')
    await router.push('/messages/comments')
    await nextTick()
    expect(host.querySelector('h1')?.textContent).toBe('评论')
    expect(host.querySelector('.mark-all-read')).toBeNull()
    expect(host.textContent).not.toContain('驳回原因')
    host.querySelector<HTMLAnchorElement>('.back-link')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(host.querySelectorAll('.message-shortcut')).toHaveLength(3)
  })

  it('游客系统消息页提供保留站内目标的登录入口', async () => {
    await mount('/messages/system')
    expect(host.querySelector('h1')?.textContent).toBe('系统通知')
    const href = (await captureLinkTarget(
      app,
      host.querySelector<HTMLAnchorElement>('.empty-action'),
    ))!
    const target = new URL(href, 'http://localhost')
    expect(target.pathname).toBe('/login')
    expect(target.searchParams.get('redirect')).toBe('/messages/system')
    expect(host.querySelector('input, textarea')).toBeNull()
    expect(host.textContent).toContain('即将开放')
  })

  it('已登录用户直达分类仍看到待开放说明，发现美食入口真实返回首页', async () => {
    const router = await mount('/messages/followers', true)
    expect(host.querySelector('h1')?.textContent).toBe('新增关注')
    expect(host.textContent).not.toContain('登录账号')
    host.querySelector<HTMLAnchorElement>('.empty-action')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('仅在整个会话列表为空时展示统一空态', async () => {
    await mount('/messages', true, [])
    expect(host.querySelector('.session-list')).toBeNull()
    expect(host.querySelectorAll('.session-empty')).toHaveLength(1)
    expect(host.querySelectorAll('.message-shortcut')).toHaveLength(3)
    expect(host.textContent).not.toContain('私信')
  })

  it('收到会话摘要后展示时间和未读数，摘要中的HTML以普通文本呈现', async () => {
    await mount('/messages', true, [
      {
        id: 'system',
        title: '系统通知',
        preview: '<img src=x onerror=alert(1)> 审核结果',
        timeLabel: '昨天',
        unreadCount: 120,
      },
    ])
    expect(host.querySelector('.session-time')?.textContent).toBe('昨天')
    expect(host.querySelector('.session-unread')?.textContent).toBe('99+')
    expect(host.querySelector('.session-unread')?.getAttribute('aria-label')).toBe('120 条未读消息')
    expect(host.querySelector('.session-preview')?.textContent).toContain('<img')
    expect(host.querySelector('.session-preview img')).toBeNull()
    expect(host.querySelector('.session-empty')).toBeNull()
  })

  it('旧审核地址归入系统消息，旧私信地址返回中心且不再挂载聊天页面', async () => {
    const router = await mount('/messages/reviews')
    expect(router.currentRoute.value.path).toBe('/messages/system')
    await router.push('/messages/private')
    await nextTick()
    expect(router.currentRoute.value.path).toBe('/messages')
    expect(host.querySelector('.session-row')?.textContent).toContain('系统通知')
    expect(host.textContent).not.toContain('私信')
  })

  /** 使用受控页面状态验证用户动作，不向真实通知接口写入。 */
  async function inbox(category = 'likes', records: NotificationItem[] = [message()]) {
    const state = reactive({
      connected: true,
      category,
      authenticated: true,
      busy: false,
      loading: false,
      error: '',
      actionError: '',
      deletingId: undefined as string | undefined,
      inbox: {
        userId: '42',
        category: 'LIKES',
        records,
        total: records.length,
        page: 1,
        pageSize: 10,
        totalPages: 1,
        unreadCount: 1,
        unreadCounts: { SYSTEM: 0, COMMENTS: 0, LIKES: 1, FOLLOWERS: 0 },
        latestSystem: null,
      } as NotificationPage,
    })
    const events: Array<[string, unknown?]> = []
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: { template: '<p />' } }],
    })
    await router.push(`/messages/${category}`)
    renderPage(
      () =>
        h(MessageCenter, {
          ...state,
          onRead: (item) => events.push(['read', item]),
          onRemove: (item) => {
            events.push(['remove', item])
            state.deletingId = item.id
          },
          onCancelRemove: () => {
            events.push(['cancel'])
            state.deletingId = undefined
          },
          onConfirmRemove: () => events.push(['confirm']),
          onReadAll: () => events.push(['readAll']),
          onRefresh: () => events.push(['refresh']),
          onPage: (page) => events.push(['page', page]),
        }),
      router,
    )
    return { state, events }
  }

  function message(overrides: Partial<NotificationItem> = {}): NotificationItem {
    return {
      id: '71',
      requestId: null,
      category: 'LIKES',
      notificationType: 'REVIEW_LIKED',
      sourceId: '81',
      reviewId: '91',
      actorUserId: '9007199254740993',
      actorUsername: '测试同学',
      requestType: null,
      decision: null,
      restaurantName: null,
      rejectReason: null,
      createdAt: '2026-11-17T08:00:00Z',
      readAt: null,
      ...overrides,
    }
  }

  const click = async (selector: string) => {
    host.querySelector<HTMLButtonElement>(selector)!.click()
    await nextTick()
  }

  it('分类头只留箭头，隐藏零未读操作；读取整行和全部已读分别传递原对象与事件', async () => {
    const { state, events } = await inbox()
    expect(host.querySelector('.back-link')?.textContent?.trim()).toBe('')
    expect(host.querySelector('.category-unread')?.getAttribute('aria-label')).toBe('1 条未读消息')
    expect(host.textContent).not.toMatch(/刷新|查看获赞内容|删除消息/)
    expect(host.querySelector('.notification-unread')?.getAttribute('aria-label')).toBe('未读消息')
    await click('.notification-card')
    expect(events[0]).toEqual(['read', state.inbox.records[0]])
    await click('.mark-all-read')
    expect(events[1]).toEqual(['readAll'])
    state.inbox.unreadCount = 0
    state.inbox.records[0]!.readAt = '2026-11-17T09:00:00Z'
    await nextTick()
    expect(host.querySelector('.category-unread, .mark-all-read, .notification-unread')).toBeNull()
  })

  it('更多及删除不打开消息，取消与Esc恢复入口焦点，确认才转交删除', async () => {
    const { events } = await inbox()
    await click('.notification-more')
    expect(host.querySelector('.notification-more')?.getAttribute('aria-expanded')).toBe('true')
    await click('.notification-menu button')
    expect(events.map((e) => e[0])).toEqual(['remove'])
    expect(host.querySelector('.notification-menu')).toBeNull()
    const cancel = host.querySelector<HTMLButtonElement>('.notification-delete-confirm button')!
    expect(document.activeElement).toBe(cancel)
    cancel.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await nextTick()
    expect(events.map((e) => e[0])).toEqual(['remove', 'cancel'])
    expect(document.activeElement).toBe(host.querySelector('.notification-more'))
    await click('.notification-more')
    await click('.notification-menu button')
    await click('.confirm-delete')
    expect(events.map((e) => e[0])).toEqual(['remove', 'cancel', 'remove', 'confirm'])
  })

  it('键盘展开与Esc、外点、焦点离开均收起菜单；换分类销毁旧消息操作', async () => {
    const { state, events } = await inbox()
    const trigger = host.querySelector<HTMLButtonElement>('.notification-more')!
    trigger.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }))
    await nextTick()
    expect(document.activeElement).toBe(host.querySelector('.notification-menu button'))
    document.activeElement!.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }),
    )
    await nextTick()
    expect(document.activeElement).toBe(trigger)
    expect(host.querySelector('.notification-menu')).toBeNull()
    await click('.notification-more')
    document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    await nextTick()
    expect(host.querySelector('.notification-menu')).toBeNull()
    await click('.notification-more')
    host.querySelector<HTMLAnchorElement>('.back-link')!.focus()
    await nextTick()
    expect(host.querySelector('.notification-menu')).toBeNull()
    await click('.notification-more')
    state.category = 'followers'
    state.inbox.records = []
    state.inbox.total = 0
    state.inbox.unreadCount = 0
    await nextTick()
    expect(host.querySelector('.notification-menu')).toBeNull()
    expect(events).toEqual([])
  })

  it('忙碌时不重复提交或取消；删除失败保留确认与重试，错误仅以文本展示', async () => {
    const { state, events } = await inbox()
    await click('.notification-more')
    await click('.notification-menu button')
    state.busy = true
    await nextTick()
    await click('.notification-card')
    await click('.confirm-delete')
    host
      .querySelector('.notification-delete-confirm')!
      .dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await nextTick()
    expect(events.map((e) => e[0])).toEqual(['remove'])
    state.busy = false
    state.actionError = '<img src=x> 删除未确认'
    await nextTick()
    expect(host.querySelector('.notification-delete-confirm')).not.toBeNull()
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('<img src=x>')
    expect(host.querySelector('[role="alert"] img')).toBeNull()
    await click('.confirm-delete')
    expect(events.at(-1)?.[0]).toBe('confirm')
  })

  it('分页与读取失败恢复独立工作，保留当前页和第一页兜底', async () => {
    const { state, events } = await inbox()
    state.error = '暂时无法读取'
    state.inbox = null as unknown as NotificationPage
    await nextTick()
    await click('.notification-status button')
    expect(events).toEqual([['refresh']])
    state.error = ''
    state.inbox = {
      userId: '42',
      category: 'LIKES',
      records: [],
      total: 11,
      page: 2,
      pageSize: 10,
      totalPages: 2,
      unreadCount: 0,
      unreadCounts: { SYSTEM: 0, COMMENTS: 0, LIKES: 0 },
      latestSystem: null,
    }
    await nextTick()
    const buttons = [...host.querySelectorAll<HTMLButtonElement>('.notification-pagination button')]
    expect(buttons[1]?.disabled).toBe(true)
    buttons[0]!.click()
    buttons[2]!.click()
    expect(events.slice(1)).toEqual([
      ['page', 1],
      ['page', 1],
    ])
    expect(host.textContent).toContain('当前页记录已变化')
  })

  it.each([
    ['comments', message({ category: 'COMMENTS' }), '测试同学', '回复了你的点评'],
    ['likes', message({ notificationType: 'REPLY_LIKED' }), '测试同学', '赞了你的回复'],
    [
      'followers',
      message({ category: 'FOLLOWERS', notificationType: 'USER_FOLLOWED' }),
      '测试同学',
      '关注了你',
    ],
    [
      'system',
      message({
        category: 'SYSTEM',
        actorUserId: null,
        actorUsername: null,
        requestType: 'CREATE',
        decision: 'APPROVED',
        restaurantName: '银杏小馆',
      }),
      '新店投稿审核通过',
      '银杏小馆',
    ],
    [
      'system',
      message({
        category: 'SYSTEM',
        approvalMode: 'LINKED_EXISTING',
        actorUserId: null,
        actorUsername: null,
        restaurantName: '银杏小馆',
      }),
      '店铺已收录',
      '银杏小馆',
    ],
    [
      'system',
      message({
        category: 'SYSTEM',
        notificationType: 'ANNOUNCEMENT',
        actorUserId: null,
        actorUsername: null,
        announcementTitle: '<b>公告</b>',
      }),
      '系统公告',
      '<b>公告</b>',
    ],
    [
      'system',
      message({
        category: 'SYSTEM',
        notificationType: 'REPORT_RESULT',
        actorUserId: null,
        actorUsername: null,
        decision: 'DISMISSED',
      }),
      '举报处理结果',
      '举报未认定违规',
    ],
    [
      'system',
      message({
        category: 'SYSTEM',
        notificationType: 'REPORT_RESULT',
        actorUserId: null,
        actorUsername: null,
        decision: 'REMOVED',
      }),
      '举报处理结果',
      '举报已处理：内容已删除',
    ],
    [
      'system',
      message({
        category: 'SYSTEM',
        actorUserId: null,
        actorUsername: null,
        requestType: 'UPDATE',
        decision: 'REJECTED',
        restaurantName: '银杏小馆',
        rejectReason: '<img>需核实地址',
      }),
      '资料纠错审核未通过',
      '银杏小馆',
    ],
  ])('不同消息保留真实处理语义和文本边界：%s / %s', async (category, item, title, detail) => {
    await inbox(category, [item])
    expect(host.querySelector('.notification-title')?.textContent).toBe(title)
    expect(host.querySelector('.notification-detail')?.textContent).toBe(detail)
    expect(host.querySelector('.notification-copy img, .notification-card b')).toBeNull()
    const image = host.querySelector('.notification-avatar img')
    if (category !== 'system')
      expect(image?.getAttribute('src')).toContain(`/api/users/${item.actorUserId}/avatar`)
    else expect(image).toBeNull()
    expect(host.querySelector('time')?.getAttribute('datetime')).toBe(item.createdAt)
    if (item.rejectReason)
      expect(host.querySelector('.notification-reason')?.textContent).toContain(item.rejectReason)
    if (category !== 'system')
      expect(host.querySelector('.notification-avatar')?.textContent?.trim()).toBe('测')
  })
})
