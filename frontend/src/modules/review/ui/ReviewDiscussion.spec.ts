import { createApp, h, nextTick, reactive, ref, type App } from 'vue'
import { createPinia, disposePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useLoginStore } from '@/modules/auth'
import { clearAccessToken } from '@/shared/api/authSession'
import { reviewGatewayKey, unavailableReviewGateway, type ReplyPage } from '../model/review'
import { replyScopeVisibleKey } from '../model/replyEditor'
import ReviewDiscussion from './ReviewDiscussion.vue'

// 只替换与本轮无关的互动传输；详情、预览、编辑器及删除确认使用真实组件。
vi.mock('@/modules/community', () => ({
  ContentActions: { render: () => null },
  UserIdentity: {
    props: ['username'],
    setup: (props: { username: string }) => () => h('span', props.username),
  },
}))
const row = (id: string, userId = '11') => ({
  id,
  author: { id: userId, username: '同学' + userId },
  body: '回复正文' + id,
  createdAt: '2026-11-16T01:00:00Z',
})
const replyPage = (ids = ['1', '2', '3'], more = false): ReplyPage => ({
  records: ids.map((id) => row(id)),
  hasMore: more,
  nextCursor: more ? 'next' : null,
})
const settle = async () => {
  for (let i = 0; i < 18; i++) await nextTick()
}
const props = reactive({
  reviewId: '10',
  username: '原作者',
  standalone: false,
  pending: false,
  focusedReply: undefined as ReplyPage['records'][number] | undefined,
})
const visible = ref(true)
let app: App | undefined, host: HTMLDivElement, pinia: ReturnType<typeof createPinia>
const list = vi.fn(),
  reply = vi.fn(),
  removeReply = vi.fn()
const originalShow = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal')
const originalClose = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'close')
const modal = () => document.querySelector<HTMLDialogElement>('.discussion-dialog')!
const button = (name: string, root: ParentNode = modal()) =>
  [...root.querySelectorAll<HTMLButtonElement>('button')].find(
    (b) => b.textContent?.trim() === name,
  )!
async function open() {
  host.querySelector<HTMLButtonElement>('.discussion-reply-action')!.click()
  await settle()
}
async function close() {
  modal().querySelector<HTMLButtonElement>('[aria-label="返回点评列表"]')!.click()
  await settle()
}
async function fill(value: string) {
  const input = modal().querySelector('textarea')!
  input.value = value
  input.dispatchEvent(new Event('input'))
  await settle()
}
async function send() {
  modal()
    .querySelector('form')!
    .dispatchEvent(new Event('submit', { cancelable: true }))
  await settle()
}
async function mount(loggedIn = true) {
  const auth = useLoginStore(pinia)
  if (loggedIn)
    auth.acceptSession({
      accessToken: 'session-42',
      tokenType: 'Bearer',
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id: 42, username: '本人', roles: ['ROLE_USER'] },
    })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }],
  })
  await router.push('/account/reviews?page=2')
  app = createApp({
    render: () =>
      h(ReviewDiscussion, props, {
        original: () => h('p', { class: 'test-original' }, '原点评正文'),
      }),
  })
    .use(pinia)
    .use(router)
  app.provide(replyScopeVisibleKey, visible)
  app.provide(reviewGatewayKey, {
    ...unavailableReviewGateway,
    listReplies: list,
    reply,
    removeReply,
  })
  app.mount(host)
  await settle()
  return { auth, router }
}
beforeEach(() => {
  clearAccessToken()
  vi.resetAllMocks()
  props.reviewId = '10'
  props.standalone = props.pending = false
  props.focusedReply = undefined
  visible.value = true
  pinia = createPinia()
  host = document.createElement('div')
  document.body.append(host)
  list.mockResolvedValue(replyPage())
  reply.mockResolvedValue(undefined)
  removeReply.mockResolvedValue(undefined)
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
  host.remove()
  disposePinia(pinia)
  clearAccessToken()
  document.documentElement.style.overflow = ''
  vi.unstubAllGlobals()
  vi.useRealTimers()
  for (const [name, descriptor] of [
    ['showModal', originalShow],
    ['close', originalClose],
  ] as const) {
    if (descriptor) Object.defineProperty(HTMLDialogElement.prototype, name, descriptor)
    else Reflect.deleteProperty(HTMLDialogElement.prototype, name)
  }
})

describe('点评回复预览与独立详情', () => {
  it('通知直接打开同一详情；指定回复不在首批也能定位，后续分页去重且不扫描所有页', async () => {
    vi.useFakeTimers()
    props.standalone = true
    props.pending = true
    props.focusedReply = row('777')
    list
      .mockResolvedValueOnce(replyPage(['1', '2'], true))
      .mockResolvedValueOnce(replyPage(['777', '3']))
    await mount()
    expect(modal().open).toBe(true)
    expect(host.querySelector('.discussion-preview, .discussion-reply-action')).toBeNull()
    expect(list).not.toHaveBeenCalled()
    props.pending = false
    await settle()
    expect(list).toHaveBeenCalledTimes(1)
    expect(modal().querySelectorAll('[data-target-reply="true"]')).toHaveLength(1)
    expect(modal().querySelector('.target-reply-highlight')?.textContent).toContain('回复正文777')
    button('加载更多回复').click()
    await settle()
    expect(list).toHaveBeenCalledTimes(2)
    expect(modal().querySelectorAll('.reply-list > li')).toHaveLength(4)
    expect(modal().querySelectorAll('[data-target-reply="true"]')).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(2400)
    expect(modal().querySelector('.target-reply-highlight')).toBeNull()
  })

  it('通知定位的本人回复可正常删除，删除成功后不会被目标快照重新插回', async () => {
    props.standalone = true
    props.focusedReply = row('777', '42')
    list.mockResolvedValue(replyPage(['1'], true))
    await mount()
    const target = modal().querySelector('[data-target-reply="true"]')!
    target.querySelector<HTMLButtonElement>('.more-review-actions')!.click()
    await settle()
    button('删除回复', target).click()
    await settle()
    button('确认删除回复', target).click()
    await settle()
    expect(removeReply).toHaveBeenCalledWith('777', expect.any(AbortSignal))
    expect(modal().querySelector('[data-target-reply="true"]')).toBeNull()
    expect(modal().textContent).not.toContain('回复正文777')
  })
  it('手机键盘缩小可视视口时面板跟随，关闭后释放监听', async () => {
    const viewport = Object.assign(new EventTarget(), { height: 700, offsetTop: 0, scale: 1 })
    vi.stubGlobal('visualViewport', viewport)
    vi.stubGlobal('innerWidth', 390)
    await mount()
    await open()
    expect(modal().style.height).toBe('700px')
    viewport.height = 400
    viewport.offsetTop = 20
    viewport.dispatchEvent(new Event('resize'))
    expect(modal().style.height).toBe('400px')
    expect(modal().style.top).toBe('20px')
    await close()
    viewport.dispatchEvent(new Event('resize'))
    expect(modal().style.height).toBe('')
    expect(modal().style.top).toBe('')
  })
  it('列表只预览两条；详情复用首批数据，关闭保留草稿和失败UUID并恢复焦点及滚动锁', async () => {
    reply.mockRejectedValueOnce(new Error('发布中断')).mockResolvedValueOnce(undefined)
    await mount()
    expect(host.querySelectorAll('.preview-line')).toHaveLength(2)
    expect(host.textContent).toContain('共3条回复')
    expect(host.querySelector('textarea')).toBeNull()
    document.documentElement.style.overflow = 'auto'
    await open()
    expect(modal().open).toBe(true)
    expect(modal().textContent).toContain('原点评正文')
    expect(modal().querySelectorAll('li')).toHaveLength(3)
    expect(list).toHaveBeenCalledTimes(1)
    await fill('关闭之后重试的草稿')
    await send()
    expect(modal().textContent).toContain('发布中断')
    const requestId = reply.mock.calls[0]![2]
    await close()
    expect(modal().open).toBe(false)
    expect(document.documentElement.style.overflow).toBe('auto')
    expect(document.activeElement).toBe(host.querySelector('.discussion-reply-action'))
    await open()
    expect(modal().querySelector('textarea')!.value).toBe('关闭之后重试的草稿')
    list.mockResolvedValueOnce(replyPage(['1', '2', '3', '4']))
    await send()
    expect(reply.mock.calls[1]![2]).toBe(requestId)
    expect(modal().querySelector('textarea')!.value).toBe('')
    expect(host.textContent).toContain('共4条回复')
  })
  it('未读完不伪造总数；分页去重，全部读完才显示确切数量', async () => {
    list
      .mockResolvedValueOnce(replyPage(['1', '2'], true))
      .mockResolvedValueOnce(replyPage(['2', '3']))
    await mount()
    expect(host.textContent).toContain('查看全部回复')
    expect(host.textContent).not.toContain('共2条回复')
    await open()
    button('加载更多回复').click()
    await settle()
    expect(list.mock.calls[1]![2]).toBe('next')
    expect(modal().querySelectorAll('li')).toHaveLength(3)
    expect(host.textContent).toContain('共3条回复')
  })
  it('关闭取消在途GET，迟到结果不污染重开后的记录', async () => {
    let resolve!: (value: ReplyPage) => void
    list
      .mockImplementationOnce(
        () =>
          new Promise<ReplyPage>((r) => {
            resolve = r
          }),
      )
      .mockResolvedValueOnce(replyPage(['8']))
    await mount()
    const signal = list.mock.calls[0]![1] as AbortSignal
    await open()
    expect(signal.aborted).toBe(true)
    expect(modal().textContent).toContain('回复正文8')
    resolve(replyPage(['1']))
    await settle()
    expect(modal().textContent).not.toContain('回复正文1')
    await close()
    await open()
    expect(modal().textContent).toContain('回复正文8')
  })
  it('预览读取失败可重试，失败不伪装为空回复', async () => {
    list.mockRejectedValueOnce(new Error('读取失败')).mockResolvedValueOnce(replyPage())
    await mount()
    expect(host.textContent).toContain('回复加载失败，点击重试')
    expect(host.textContent).not.toContain('共0条回复')
    button('回复加载失败，点击重试', host).click()
    await settle()
    expect(host.textContent).toContain('共3条回复')
  })
  it('仅本人回复显示删除入口，失败可重试且始终删除回复ID', async () => {
    list.mockResolvedValueOnce({ ...replyPage(), records: [row('1', '42'), row('2')] })
    removeReply.mockRejectedValueOnce(new Error('删除中断')).mockResolvedValueOnce(undefined)
    await mount()
    await open()
    expect(
      [...modal().querySelectorAll('button')].filter((b) => b.textContent?.trim() === '删除回复'),
    ).toHaveLength(1)
    button('删除回复').click()
    await settle()
    button('取消删除').click()
    await settle()
    expect(removeReply).not.toHaveBeenCalled()
    button('删除回复').click()
    await settle()
    button('确认删除回复').click()
    await settle()
    expect(modal().textContent).toContain('删除中断')
    list.mockResolvedValueOnce(replyPage(['2']))
    button('确认删除回复').click()
    await settle()
    expect(removeReply.mock.calls.map((call) => call[0])).toEqual(['1', '1'])
    expect(host.textContent).toContain('共1条回复')
  })
  it('发送成功但刷新失败只重读，不重复发评', async () => {
    await mount()
    await open()
    await fill('已发布正文')
    list.mockRejectedValueOnce(new Error('读取中断'))
    await send()
    expect(modal().textContent).toContain('回复已发送，但列表刷新失败')
    expect(modal().querySelector('textarea')!.value).toBe('')
    list.mockResolvedValueOnce(replyPage(['4']))
    button('重试加载回复').click()
    await settle()
    expect(reply).toHaveBeenCalledTimes(1)
    expect(host.textContent).toContain('共1条回复')
  })
  it('关闭期间发布成功不重开详情，列表预览仍更新', async () => {
    let resolve!: () => void
    reply.mockImplementationOnce(
      () =>
        new Promise<void>((r) => {
          resolve = r
        }),
    )
    await mount()
    await open()
    await fill('在途的回复')
    await send()
    await close()
    list.mockResolvedValue(replyPage(['1', '2', '3', '4']))
    resolve()
    await settle()
    expect(modal().open).toBe(false)
    expect(host.textContent).toContain('共4条回复')
    await open()
    expect(modal().querySelector('textarea')!.value).toBe('')
  })
  it('路由离开和换号关闭详情，旧发布回执不进入新账号', async () => {
    let resolve!: () => void
    reply.mockImplementationOnce(
      () =>
        new Promise<void>((r) => {
          resolve = r
        }),
    )
    const { auth, router } = await mount()
    await open()
    await fill('旧账号文字')
    await send()
    auth.acceptSession({
      accessToken: 'session-43',
      tokenType: 'Bearer',
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id: 43, username: '另一人', roles: ['ROLE_USER'] },
    })
    await settle()
    resolve()
    await settle()
    expect(modal().open).toBe(false)
    expect(modal().querySelector('textarea')).toBeNull()
    await open()
    expect(modal().querySelector('textarea')!.value).toBe('')
    expect(modal().textContent).not.toContain('回复已发送')
    await router.push('/account/favorites')
    await settle()
    expect(modal().open).toBe(false)
    expect(document.documentElement.style.overflow).toBe('')
  })
  it('游客可阅读，输入入口转到登录并保留安全来源地址', async () => {
    const { router } = await mount(false)
    await open()
    expect(modal().textContent).toContain('回复正文1')
    expect(modal().querySelector<HTMLFormElement>('form')!.style.display).toBe('none')
    button('登录后回复').click()
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/login'))
    expect(router.currentRoute.value.query.redirect).toBe('/account/reviews?page=2')
  })
})
