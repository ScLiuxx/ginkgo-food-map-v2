import { createApp, h, nextTick, type App, type Slots } from 'vue'
import { createPinia, disposePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useLoginStore } from '@/modules/auth'
import { clearAccessToken } from '@/shared/api/authSession'
import { fetchNotificationReview, type ReviewDetail } from '../api/reviewDetailApi'
import ReviewNotificationDetail from './ReviewNotificationDetail.vue'

vi.mock('../api/reviewDetailApi', () => ({ fetchNotificationReview: vi.fn() }))
// 面板布局、回复草稿及分页由ReviewDiscussion测试；这里验证私有详情装配与迟到响应隔离。
vi.mock('./ReviewDiscussion.vue', () => ({
  default: {
    props: ['pending', 'focusedReply'],
    setup:
      (_: unknown, { slots }: { slots: Slots }) =>
      () =>
        h('section', slots.original?.({ focusInput: () => undefined })),
  },
}))
vi.mock('./ReviewDetailOriginal.vue', () => ({
  default: {
    props: ['item'],
    setup: (props: { item: { body: string } }) => () => h('article', props.item.body),
  },
}))
const settle = async () => {
  for (let i = 0; i < 12; i++) await nextTick()
}
const detail = { item: { body: '准确原点评' }, restaurantId: '1', windowId: null } as ReviewDetail
let app: App | undefined, host: HTMLDivElement, pinia: ReturnType<typeof createPinia>
let origin: HTMLButtonElement
const closed = vi.fn()
const originalClose = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'close')
async function mount() {
  const auth = useLoginStore(pinia)
  auth.acceptSession({
    accessToken: 'session-42',
    tokenType: 'Bearer',
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id: 42, username: '本人', roles: ['ROLE_USER'] },
  })
  origin.focus()
  app = createApp({
    render: () =>
      h(ReviewNotificationDetail, {
        reviewId: '91',
        notificationId: '21',
        sourceId: '121',
        type: 'COMMENT',
        onClose: closed,
      }),
  }).use(pinia)
  app.mount(host)
  await settle()
  return auth
}
beforeEach(() => {
  clearAccessToken()
  vi.resetAllMocks()
  pinia = createPinia()
  host = document.createElement('div')
  origin = document.createElement('button')
  document.body.append(origin, host)
  Object.defineProperty(HTMLDialogElement.prototype, 'close', {
    configurable: true,
    value() {
      this.open = false
    },
  })
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host.remove()
  origin.remove()
  disposePinia(pinia)
  clearAccessToken()
  if (originalClose) Object.defineProperty(HTMLDialogElement.prototype, 'close', originalClose)
  else Reflect.deleteProperty(HTMLDialogElement.prototype, 'close')
})
describe('通知详情读取边界', () => {
  it('失败保留原面板并可重试，使用准确源ID和当前凭证', async () => {
    vi.mocked(fetchNotificationReview)
      .mockRejectedValueOnce(new Error('暂时失败'))
      .mockResolvedValueOnce(detail)
    await mount()
    expect(host.textContent).toContain('暂时失败')
    expect(host.querySelector('article')).toBeNull()
    host.querySelector('button')!.click()
    await settle()
    expect(host.querySelector('article')?.textContent).toBe('准确原点评')
    expect(fetchNotificationReview).toHaveBeenLastCalledWith(
      'COMMENT',
      '121',
      '91',
      '42',
      'session-42',
      expect.any(AbortSignal),
    )
  })
  it('目标删除显示不可访问，不替换成其他点评', async () => {
    vi.mocked(fetchNotificationReview).mockRejectedValue({
      isAxiosError: true,
      response: { status: 404 },
    })
    await mount()
    expect(host.textContent).toContain('对应点评或回复已删除')
    expect(host.querySelector('article')).toBeNull()
  })
  it('关闭取消读取并恢复入口焦点，迟到结果不显示', async () => {
    let resolve!: (value: ReviewDetail) => void
    vi.mocked(fetchNotificationReview).mockReturnValue(
      new Promise((done) => {
        resolve = done
      }),
    )
    await mount()
    const signal = vi.mocked(fetchNotificationReview).mock.calls[0]![5]
    app!.unmount()
    app = undefined
    expect(signal.aborted).toBe(true)
    expect(document.activeElement).toBe(origin)
    resolve(detail)
    await settle()
    expect(host.textContent).toBe('')
  })
  it('换号立即通知关闭，旧账号迟到结果不进入新账号页面', async () => {
    let resolve!: (value: ReviewDetail) => void
    vi.mocked(fetchNotificationReview).mockReturnValue(
      new Promise((done) => {
        resolve = done
      }),
    )
    const auth = await mount()
    auth.acceptSession({
      accessToken: 'session-88',
      tokenType: 'Bearer',
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id: 88, username: '另一个账号', roles: ['ROLE_USER'] },
    })
    expect(closed).toHaveBeenCalled()
    resolve(detail)
    await settle()
    expect(host.querySelector('article')).toBeNull()
  })
  it('来源按钮被已读重读替换后，关闭仍恢复到当前同一条消息', async () => {
    vi.mocked(fetchNotificationReview).mockResolvedValue(detail)
    await mount()
    origin.remove()
    const row = document.createElement('li')
    row.dataset.notificationId = '21'
    const replacement = document.createElement('button')
    replacement.className = 'notification-card'
    row.append(replacement)
    document.body.append(row)
    app!.unmount()
    app = undefined
    await settle()
    expect(document.activeElement).toBe(replacement)
    row.remove()
  })
})
