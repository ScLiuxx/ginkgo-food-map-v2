import { createApp, h, nextTick, reactive, ref } from 'vue'
import { activeReplyEditorKey, replyScopeVisibleKey } from './replyEditor'
import type { App } from 'vue'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ReplyThread from '../ui/ReplyThread.vue'
import { reviewGatewayKey, unavailableReviewGateway } from './review'
import type { ReplyPage, ReviewGateway } from './review'
import { clearAccessToken, setAccessToken } from '@/shared/api/authSession'
import { useLoginStore } from '@/modules/auth/model/login.store'

let app: App, host: HTMLDivElement
const props = reactive({ reviewId: '10', username: '点评作者', combined: false })
const settle = async () => {
  for (let i = 0; i < 15; i++) await nextTick()
}
const page = (id = '1', more = false): ReplyPage => ({
  records: [
    {
      id,
      author: { id: '11', username: '同学' },
      body: `回复${id}`,
      createdAt: '2026-10-30T01:00:00Z',
    },
  ],
  hasMore: more,
  nextCursor: more ? 'next' : null,
})
async function mount(
  list = vi.fn().mockResolvedValue(page()),
  reply = vi.fn().mockResolvedValue(undefined),
  loggedIn = true,
  multiple = false,
  combined = false,
) {
  props.reviewId = '10'
  props.combined = combined
  clearAccessToken()
  const pinia = createPinia(),
    auth = useLoginStore(pinia)
  if (loggedIn) {
    setAccessToken('account-a')
    auth.user = { id: 11, username: '同学', roles: ['ROLE_USER'] }
  }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }],
  })
  await router.push('/restaurants/20?from=home')
  host = document.createElement('div')
  document.body.append(host)
  const scopeVisible = ref(true)
  app = createApp({
    render: () =>
      multiple
        ? [h(ReplyThread, props), h(ReplyThread, { reviewId: '11', username: '另一位同学' })]
        : h(ReplyThread, props),
  })
  app.use(pinia)
  app.use(router)
  app.provide(activeReplyEditorKey, ref<string | null>(null))
  app.provide(replyScopeVisibleKey, scopeVisible)
  app.provide(reviewGatewayKey, {
    ...unavailableReviewGateway,
    listReplies: list as ReviewGateway['listReplies'],
    reply: reply as ReviewGateway['reply'],
  })
  app.mount(host)
  await settle()
  return { list, reply, router, scopeVisible }
}
async function toggle(open: boolean) {
  const name = open ? '查看回复' : '收起回复'
  ;[...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === name)!.click()
  await settle()
}
async function text(body: string) {
  if (host.querySelector<HTMLFormElement>('form')!.style.display === 'none') {
    ;[...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === '回复')!.click()
    await settle()
  }
  const input = host.querySelector('textarea')!
  input.value = body
  input.dispatchEvent(new Event('input'))
  await settle()
}
async function send() {
  host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
  await settle()
}
afterEach(() => {
  app?.unmount()
  host?.remove()
  clearAccessToken()
})

describe('reply thread lifecycle', () => {
  it('合并入口同时展开回复与编辑，收起重开保留失败正文和UUID', async () => {
    const reply = vi
      .fn()
      .mockRejectedValueOnce(new Error('发送中断'))
      .mockResolvedValueOnce(undefined)
    const { list } = await mount(undefined, reply, true, false, true)
    const action = () => host.querySelector<HTMLButtonElement>('.thread-actions > button')!
    expect(host.querySelectorAll('.thread-actions > button')).toHaveLength(1)
    expect(list).not.toHaveBeenCalled()
    action().click()
    await settle()
    expect(action().getAttribute('aria-expanded')).toBe('true')
    expect(host.querySelector<HTMLFormElement>('form')!.style.display).not.toBe('none')
    expect(list).toHaveBeenCalledTimes(1)
    await text('收起后继续重试的正文')
    await send()
    const requestId = reply.mock.calls[0]![2]
    action().click()
    await settle()
    expect(host.querySelector<HTMLFormElement>('form')!.style.display).toBe('none')
    action().click()
    await settle()
    expect(host.querySelector('textarea')!.value).toBe('收起后继续重试的正文')
    expect(list).toHaveBeenCalledTimes(1)
    await send()
    expect(reply.mock.calls[1]![2]).toBe(requestId)
    expect(host.textContent).toContain('回复已发送')
  })

  it('合并入口收起取消旧读取，重开不接受旧结果；游客仍能阅读后选择登录回复', async () => {
    let resolve!: (value: ReplyPage) => void
    const list = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise<ReplyPage>((r) => {
            resolve = r
          }),
      )
      .mockResolvedValueOnce(page('2'))
    const { router } = await mount(list, undefined, false, false, true)
    const action = host.querySelector<HTMLButtonElement>('.thread-actions > button')!
    action.click()
    await settle()
    const signal = list.mock.calls[0]![1] as AbortSignal
    action.click()
    await settle()
    expect(signal.aborted).toBe(true)
    action.click()
    await settle()
    resolve(page('1'))
    await settle()
    expect(host.textContent).toContain('回复2')
    expect(host.textContent).not.toContain('回复1')
    expect(host.querySelector<HTMLFormElement>('form')!.style.display).toBe('none')
    ;[...host.querySelectorAll<HTMLButtonElement>('button')]
      .find((b) => b.textContent?.trim() === '登录后回复')!
      .click()
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/login'))
  })
  it('loads only on expansion and appends pages without duplicate records', async () => {
    const list = vi
      .fn()
      .mockResolvedValueOnce(page('1', true))
      .mockResolvedValueOnce({
        ...page('2'),
        records: [page('1').records[0]!, page('2').records[0]!],
      })
    await mount(list)
    expect(list).not.toHaveBeenCalled()
    await toggle(true)
    ;[...host.querySelectorAll('button')]
      .find((b) => b.textContent?.trim() === '加载更多回复')!
      .click()
    await settle()
    expect(list.mock.calls[1]![2]).toBe('next')
    expect(host.querySelectorAll('li')).toHaveLength(2)
    await toggle(false)
    await toggle(true)
    expect(list).toHaveBeenCalledTimes(2)
  })
  it('keeps text and UUID on failure, clears only after success and refreshes locally', async () => {
    const reply = vi
      .fn()
      .mockRejectedValueOnce(new Error('网络失败'))
      .mockResolvedValueOnce(undefined)
    const { list } = await mount(undefined, reply)
    await toggle(true)
    await text('  好吃🥟  ')
    await send()
    expect(host.textContent).toContain('网络失败')
    expect(host.querySelector('textarea')!.value).toBe('  好吃🥟  ')
    await send()
    expect(reply.mock.calls[0]!.slice(0, 3)).toEqual(reply.mock.calls[1]!.slice(0, 3))
    expect(reply.mock.calls[0]![0]).toBe('10')
    expect(reply.mock.calls[0]![1]).toBe('好吃🥟')
    expect(host.querySelector('textarea')!.value).toBe('')
    expect(host.textContent).toContain('回复已发送')
    expect(list).toHaveBeenCalledTimes(2)
    expect(host.querySelector<HTMLDivElement>('.thread-content')!.style.display).not.toBe('none')
    expect(host.querySelector<HTMLFormElement>('form')!.style.display).toBe('none')
  })
  it('generates another UUID when a failed draft is changed', async () => {
    const reply = vi.fn().mockRejectedValue(new Error('失败'))
    await mount(undefined, reply)
    await toggle(true)
    await text('第一条')
    await send()
    await text('第二条')
    await send()
    expect(reply.mock.calls[0]![2]).not.toBe(reply.mock.calls[1]![2])
  })
  it('distinguishes successful publication from failed reload', async () => {
    const list = vi
      .fn()
      .mockResolvedValueOnce(page())
      .mockRejectedValueOnce(new Error('加载失败'))
      .mockResolvedValueOnce(page('2'))
    const { reply } = await mount(list)
    await toggle(true)
    await text('已发送的正文')
    await send()
    expect(host.textContent).toContain('回复已发送，但列表刷新失败')
    expect(host.querySelector('textarea')!.value).toBe('')
    ;[...host.querySelectorAll('button')]
      .find((b) => b.textContent?.trim() === '重试加载回复')!
      .click()
    await settle()
    expect(reply).toHaveBeenCalledOnce()
    expect(host.querySelectorAll('li')).toHaveLength(1)
  })
  it('cancels collapsed reads and ignores their late responses', async () => {
    let resolve!: (value: ReplyPage) => void
    const list = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise<ReplyPage>((r) => {
            resolve = r
          }),
      )
      .mockResolvedValueOnce(page('2'))
    await mount(list)
    await toggle(true)
    const signal = list.mock.calls[0]![1] as AbortSignal
    await toggle(false)
    expect(signal.aborted).toBe(true)
    resolve(page())
    await settle()
    await toggle(true)
    expect(host.textContent).toContain('回复2')
    expect(host.querySelectorAll('li')).toHaveLength(1)
  })
  it('keeps the cursor after a failed next page', async () => {
    const list = vi
      .fn()
      .mockResolvedValueOnce(page('1', true))
      .mockRejectedValueOnce(new Error('分页失败'))
      .mockResolvedValueOnce(page('2'))
    await mount(list)
    await toggle(true)
    ;[...host.querySelectorAll('button')]
      .find((b) => b.textContent?.trim() === '加载更多回复')!
      .click()
    await settle()
    ;[...host.querySelectorAll('button')]
      .find((b) => b.textContent?.trim() === '重试加载回复')!
      .click()
    await settle()
    expect(list.mock.calls.map((c) => c[2])).toEqual([undefined, 'next', 'next'])
    expect(host.querySelectorAll('li')).toHaveLength(2)
  })
  it('ignores old publication after account switch and clears the old draft', async () => {
    let resolve!: () => void
    const reply = vi.fn().mockImplementation(
      () =>
        new Promise<void>((r) => {
          resolve = r
        }),
    )
    await mount(undefined, reply)
    await toggle(true)
    await text('旧账号草稿')
    await send()
    const signal = reply.mock.calls[0]![3] as AbortSignal
    setAccessToken('account-b')
    await settle()
    expect(signal.aborted).toBe(true)
    resolve()
    await settle()
    expect(host.querySelector('textarea')!.value).toBe('')
    expect(host.textContent).not.toContain('回复已发送')
  })
  it('ignores old reads after the review changes', async () => {
    let resolve!: (value: ReplyPage) => void
    const list = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise<ReplyPage>((r) => {
            resolve = r
          }),
      )
      .mockResolvedValueOnce(page('2'))
    await mount(list)
    await toggle(true)
    props.reviewId = '9'
    await settle()
    resolve(page())
    await settle()
    expect(list.mock.calls[1]![0]).toBe('9')
    expect(host.querySelectorAll('li')).toHaveLength(1)
    expect(host.textContent).toContain('回复2')
  })
  it('validates code points and blocks duplicate submissions and IME enter', async () => {
    const reply = vi.fn().mockImplementation(() => new Promise(() => {}))
    await mount(undefined, reply)
    await toggle(true)
    await text('🥟'.repeat(201))
    await send()
    expect(reply).not.toHaveBeenCalled()
    await text('🥟'.repeat(200))
    const enter = new KeyboardEvent('keydown', {
      key: 'Enter',
      isComposing: true,
      cancelable: true,
    })
    host.querySelector('textarea')!.dispatchEvent(enter)
    expect(reply).not.toHaveBeenCalled()
    await send()
    await send()
    expect(reply).toHaveBeenCalledOnce()
  })
  it('redirects guests to login with the original location and never auto posts', async () => {
    const { router, reply } = await mount(undefined, undefined, false)
    ;[...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === '回复')!.click()
    await settle()
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/login'))
    expect(router.currentRoute.value.query.redirect).toBe('/restaurants/20?from=home')
    expect(reply).not.toHaveBeenCalled()
  })
  it('renders reply HTML as text', async () => {
    const value = page()
    value.records[0]!.body = '<img src=x onerror=alert(1)>'
    await mount(vi.fn().mockResolvedValue(value))
    await toggle(true)
    expect(host.querySelector('.reply-body')!.textContent).toContain('<img')
    expect(host.querySelector('img')).toBeNull()
  })
  it('keeps the default card compact and reading independent of editing', async () => {
    const { list } = await mount()
    expect(host.querySelector('details')).toBeNull()
    expect(host.querySelector<HTMLFormElement>('form')!.style.display).toBe('none')
    expect(list).not.toHaveBeenCalled()
    await toggle(true)
    expect(host.querySelector<HTMLFormElement>('form')!.style.display).toBe('none')
    await text('保留草稿')
    ;[...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === '取消')!.click()
    await settle()
    expect(host.querySelector<HTMLFormElement>('form')!.style.display).toBe('none')
    ;[...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === '回复')!.click()
    await settle()
    expect(host.querySelector('textarea')!.value).toBe('保留草稿')
  })
  it('shows only one editor and preserves each review draft when switching', async () => {
    await mount(undefined, undefined, true, true)
    const threads = host.querySelectorAll('.reply-thread')
    await text('第一条草稿')
    ;[...threads[1]!.querySelectorAll('button')]
      .find((b) => b.textContent?.trim() === '回复')!
      .click()
    await settle()
    expect(threads[0]!.querySelector<HTMLFormElement>('form')!.style.display).toBe('none')
    expect(threads[1]!.querySelector<HTMLFormElement>('form')!.style.display).not.toBe('none')
    expect(threads[0]!.querySelector('textarea')!.value).toBe('第一条草稿')
    expect(threads[1]!.querySelector('textarea')!.value).toBe('')
  })
  it('stops hidden panel reads and retains its draft for reopening', async () => {
    let resolve!: (value: ReplyPage) => void
    const list = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise<ReplyPage>((r) => {
            resolve = r
          }),
      )
      .mockResolvedValueOnce(page('2'))
    const { scopeVisible } = await mount(list)
    await toggle(true)
    await text('面板内草稿')
    scopeVisible.value = false
    await settle()
    expect((list.mock.calls[0]![1] as AbortSignal).aborted).toBe(true)
    expect(host.querySelector<HTMLFormElement>('form')!.style.display).toBe('none')
    resolve(page())
    await settle()
    scopeVisible.value = true
    await settle()
    expect(host.textContent).toContain('回复2')
    expect(host.querySelector('textarea')!.value).toBe('面板内草稿')
  })
})
