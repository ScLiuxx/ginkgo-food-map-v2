import { createApp, h, nextTick, reactive, type App } from 'vue'
import { createPinia, disposePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useLoginStore } from '@/modules/auth'
import { clearAccessToken } from '@/shared/api/authSession'
import { reviewGatewayKey, unavailableReviewGateway, type ReviewItem } from '../model/review'
import ReviewPanel from './ReviewPanel.vue'

// 只替换署名和互动传输；卡片、历史、详情与回复编辑器均使用真实组件。
vi.mock('@/modules/community', () => ({
  ContentActions: { render: () => h('span', { class: 'content-actions' }, '赞') },
  UserIdentity: {
    props: ['username'],
    setup: (props: { username: string }) => () => h('span', props.username),
  },
}))
vi.mock('@/modules/account/ui/UserAvatar.vue', () => ({
  default: { render: () => h('span', { class: 'test-avatar' }) },
}))
const item: ReviewItem = {
  id: '10',
  authorId: '42',
  username: '原作者',
  createdAt: '2026-11-17T01:00:00Z',
  scores: { taste: 8, value: 8, service: 7, ambience: 9 },
  recommendation: 'RECOMMENDED',
  body: '店铺中的原点评正文',
  photos: [],
  canDelete: true,
  replies: [],
  historyCount: 1,
}
const replies = {
  records: ['1', '2', '3'].map((id) => ({
    id,
    author: { id: '11', username: '同学' },
    body: '回复正文' + id,
    createdAt: item.createdAt,
  })),
  hasMore: false,
  nextCursor: null,
}
const page = () => ({
  records: [{ ...item }],
  page: 1,
  pageSize: 8,
  totalGroups: 1,
  totalReviews: 2,
  totalPages: 1,
})
const props = reactive({ restaurantId: '501', starScale: true })
const list = vi.fn(),
  listReplies = vi.fn(),
  history = vi.fn(),
  remove = vi.fn(),
  reply = vi.fn()
let app: App | undefined, host: HTMLDivElement, pinia: ReturnType<typeof createPinia>
const showDescriptor = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal')
const closeDescriptor = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'close')
const settle = async () => {
  for (let i = 0; i < 18; i++) await nextTick()
}
const dialog = () => document.querySelector<HTMLDialogElement>('.discussion-dialog')!
const click = async (selector: string, root: ParentNode = host) => {
  root.querySelector<HTMLButtonElement>(selector)!.click()
  await settle()
}
async function mount(loggedIn = true) {
  const auth = useLoginStore(pinia)
  if (loggedIn)
    auth.acceptSession({
      accessToken: 'panel-session',
      tokenType: 'Bearer',
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id: 42, username: '本人', roles: ['ROLE_USER'] },
    })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }],
  })
  await router.push('/restaurants/501')
  app = createApp({ render: () => h(ReviewPanel, props) })
    .use(pinia)
    .use(router)
  app.provide(reviewGatewayKey, {
    ...unavailableReviewGateway,
    supportsReplies: true,
    list,
    listReplies,
    history,
    remove,
    reply,
  })
  app.mount(host)
  await settle()
  return auth
}
beforeEach(() => {
  clearAccessToken()
  vi.resetAllMocks()
  pinia = createPinia()
  host = document.createElement('div')
  document.body.append(host)
  props.restaurantId = '501'
  list.mockResolvedValue(page())
  listReplies.mockResolvedValue(replies)
  history.mockResolvedValue({
    records: [
      { ...item, id: '9', body: '历史点评正文', historyCount: 0, scoringStatus: 'history' },
    ],
    currentReviewId: '10',
    hasMore: false,
    nextCursor: null,
  })
  remove.mockResolvedValue(undefined)
  reply.mockResolvedValue(undefined)
  // jsdom仅补原生开闭；真实浏览器布局和焦点约束另做隔离验证。
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
  document.documentElement.style.overflow = ''
  vi.restoreAllMocks()
  for (const [name, descriptor] of [
    ['showModal', showDescriptor],
    ['close', closeDescriptor],
  ] as const) {
    if (descriptor) Object.defineProperty(HTMLDialogElement.prototype, name, descriptor)
    else Reflect.deleteProperty(HTMLDialogElement.prototype, name)
  }
})
describe('店铺与窗口统一点评交互', () => {
  it('点击第二张从第二张打开，只切换同一点评的照片，刷新来源后关闭', async () => {
    list.mockResolvedValue({
      ...page(),
      records: [
        {
          ...item,
          photos: [
            { id: 'a', url: '/review-10-a' },
            { id: 'b', url: '/review-10-b' },
          ],
        },
        { ...item, id: '11', authorId: '43', photos: [{ id: 'c', url: '/other-review' }] },
      ],
    })
    await mount()
    await click('.photos button:nth-child(2)')
    const viewer = document.querySelector<HTMLDialogElement>('.photo-viewer')!
    expect(viewer.open).toBe(true)
    expect(viewer.querySelector('img')!.getAttribute('src')).toBe('/review-10-b')
    expect(viewer.querySelector('.photo-counter')!.textContent).toContain('2 / 2')
    expect(viewer.querySelector<HTMLButtonElement>('[aria-label="下一张"]')!.disabled).toBe(true)
    await click('[aria-label="上一张"]', viewer)
    expect(viewer.querySelector('img')!.getAttribute('src')).toBe('/review-10-a')
    props.restaurantId = '502'
    await settle()
    expect(viewer.open).toBe(false)
    expect(document.documentElement.style.overflow).toBe('')
  })
  it('保留作者，折叠四维评分并仅预览两条回复，正文和回复打开同一原点评', async () => {
    await mount()
    expect(host.querySelector('.entry-author')?.textContent).toContain('原作者')
    expect(host.querySelector('.test-avatar')).not.toBeNull()
    expect(host.querySelector<HTMLDetailsElement>('.dimension-details')?.open).toBe(false)
    expect(host.querySelector('.chips')).toBeNull()
    expect(host.textContent).not.toContain('刷新评价')
    expect(host.querySelectorAll('.preview-line')).toHaveLength(2)
    expect(host.textContent).not.toContain('回复正文3')
    expect(host.querySelector('textarea')).toBeNull()
    await click('.review-body-link')
    expect(dialog().open).toBe(true)
    expect(dialog().querySelector('.detail-original-body')?.textContent).toBe(item.body)
    expect(dialog().querySelectorAll('.reply-list > li')).toHaveLength(3)
    expect(listReplies).toHaveBeenCalledWith('10', expect.any(AbortSignal))
    await click('[aria-label="关闭点评详情"]', dialog())
    expect(document.activeElement).toBe(host.querySelector('.review-body-link'))
    await click('.discussion-preview')
    expect(dialog().open).toBe(true)
    expect(listReplies).toHaveBeenCalledTimes(1)
  })
  it('关闭后保留输入、详情滚动和列表实例，重开仍向原点评发送回复', async () => {
    await mount()
    const card = host.querySelector('.review-card')
    await click('.discussion-reply-action')
    const input = dialog().querySelector('textarea')!
    input.value = '尚未发送的回复'
    input.dispatchEvent(new Event('input'))
    const scroll = dialog().querySelector<HTMLElement>('.discussion-scroll')!
    scroll.scrollTop = 130
    await click('[aria-label="关闭点评详情"]', dialog())
    expect(host.querySelector('.review-card')).toBe(card)
    expect(list).toHaveBeenCalledTimes(1)
    expect(document.documentElement.style.overflow).toBe('')
    await click('.review-body-link')
    expect(input.value).toBe('尚未发送的回复')
    expect(scroll.scrollTop).toBe(130)
    dialog()
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { cancelable: true }))
    await settle()
    expect(reply).toHaveBeenCalledWith(
      '10',
      '尚未发送的回复',
      expect.any(String),
      expect.any(AbortSignal),
    )
  })
  it('历史收进更多菜单，关闭回到更多入口，历史正文仍打开历史ID', async () => {
    await mount()
    expect(history).not.toHaveBeenCalled()
    await click('.more-review-actions')
    await click('.history-trigger')
    const panel = document.querySelector<HTMLDialogElement>('.history-dialog')!
    expect(panel.open).toBe(true)
    expect(history).toHaveBeenCalledWith('501', '42', expect.any(AbortSignal), undefined)
    await click('.review-body-link', panel)
    const historical = [...document.querySelectorAll<HTMLDialogElement>('.discussion-dialog')].find(
      (d) => d.open,
    )!
    expect(historical.textContent).toContain('历史点评正文')
    expect(listReplies).toHaveBeenLastCalledWith('9', expect.any(AbortSignal))
    await click('[aria-label="关闭点评详情"]', historical)
    await click('.close-history', panel)
    expect(document.activeElement).toBe(host.querySelector('.more-review-actions'))
    expect(document.documentElement.style.overflow).toBe('')
  })
  it('删除先确认，取消恢复更多焦点，失败保留确认并允许重试', async () => {
    await mount()
    remove.mockRejectedValueOnce(new Error('删除暂时失败'))
    await click('.more-review-actions')
    await click('.delete-review')
    const confirm = document.querySelector<HTMLDialogElement>('.delete-dialog')!
    expect(remove).not.toHaveBeenCalled()
    await click('.cancel-delete', confirm)
    expect(document.activeElement).toBe(host.querySelector('.more-review-actions'))
    await click('.more-review-actions')
    await click('.delete-review')
    await click('.confirm-delete', confirm)
    expect(confirm.open).toBe(true)
    expect(confirm.textContent).toContain('删除暂时失败')
    await click('.confirm-delete', confirm)
    expect(remove.mock.calls).toEqual([['10'], ['10']])
    expect(confirm.open).toBe(false)
    expect(list).toHaveBeenCalledTimes(2)
  })
  it('游客与其他账号可查看回复但不能删除原作者点评', async () => {
    const auth = await mount(false)
    expect(host.querySelector('.delete-review')).toBeNull()
    await click('.review-body-link')
    expect(dialog().textContent).toContain('回复正文1')
    expect(dialog().querySelector('.delete-review')).toBeNull()
    auth.acceptSession({
      accessToken: 'other-session',
      tokenType: 'Bearer',
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id: 99, username: '其他同学', roles: ['ROLE_USER'] },
    })
    await settle()
    expect(document.querySelector('dialog[open]')).toBeNull()
    expect(host.querySelector('.delete-review')).toBeNull()
  })
  it('删除在途禁止取消，换号关闭旧确认且不应用旧账号回执', async () => {
    let finish!: () => void
    remove.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve
        }),
    )
    const auth = await mount()
    await click('.more-review-actions')
    await click('.delete-review')
    const confirm = document.querySelector<HTMLDialogElement>('.delete-dialog')!
    await click('.confirm-delete', confirm)
    expect(confirm.querySelector<HTMLButtonElement>('.cancel-delete')?.disabled).toBe(true)
    confirm.dispatchEvent(new Event('cancel', { cancelable: true }))
    await settle()
    expect(confirm.open).toBe(true)
    auth.acceptSession({
      accessToken: 'next-session',
      tokenType: 'Bearer',
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id: 99, username: '其他同学', roles: ['ROLE_USER'] },
    })
    await settle()
    expect(confirm.open).toBe(false)
    const reads = list.mock.calls.length
    finish()
    await settle()
    expect(list).toHaveBeenCalledTimes(reads)
    expect(host.querySelector('.delete-review')).toBeNull()
  })
})
