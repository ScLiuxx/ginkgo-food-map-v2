import { createApp, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { afterEach, describe, expect, it } from 'vitest'
import MessageCenter from './MessageCenter.vue'
import type { MessageSession } from '../model/messageCategories'

/** 分类导航和登录回跳可用；真实收件箱接通前不把占位状态当成已读取的用户数据。 */
describe('消息中心前端', () => {
  let app: App
  let host: HTMLDivElement

  afterEach(() => {
    app?.unmount()
    host?.remove()
  })

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
    host = document.createElement('div')
    document.body.append(host)
    app = createApp({ render: () => h(RouterView) }).use(router)
    app.mount(host)
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
    expect(host.querySelector<HTMLButtonElement>('.mark-all-read')?.title).toContain('当前分类')
    expect(host.textContent).not.toContain('驳回原因')
    host.querySelector<HTMLAnchorElement>('.back-link')!.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(host.querySelectorAll('.message-shortcut')).toHaveLength(3)
  })

  it('游客系统消息页提供保留站内目标的登录入口', async () => {
    await mount('/messages/system')
    expect(host.querySelector('h1')?.textContent).toBe('系统通知')
    const href = host.querySelector<HTMLAnchorElement>('.empty-action')!.getAttribute('href')!
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
})
