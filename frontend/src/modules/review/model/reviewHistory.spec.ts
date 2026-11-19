import { createApp, h, nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { App } from 'vue'
import ReviewHistory from '../ui/ReviewHistory.vue'
import { reviewGatewayKey, unavailableReviewGateway } from './review'
import type { ReviewHistoryPage, ReviewItem, ReviewGateway } from './review'

const item: ReviewItem = {
  id: '10',
  authorId: '22',
  username: '同学',
  historyCount: 7,
  nextHistoryCursor: 'start',
  createdAt: '2026-10-30T00:00:00Z',
  scores: { taste: 8, value: 8, service: 8, ambience: 8 },
  recommendation: 'RECOMMENDED',
  body: '当前记录',
  photos: [],
  canDelete: false,
  replies: [],
}
const result = (id = '9', more = false): ReviewHistoryPage => ({
  currentReviewId: '10',
  records: [{ ...item, id, body: '历史' + id, scoringStatus: 'history', historyCount: 0 }],
  hasMore: more,
  nextCursor: more ? 'next' : null,
})
let app: App, host: HTMLDivElement
const settle = async () => {
  for (let i = 0; i < 12; i++) await nextTick()
}
const panel = () => document.querySelector<HTMLDialogElement>('.history-dialog')!
const button = (name: string) =>
  [...panel().querySelectorAll('button')].find((b) => b.textContent?.trim() === name)!
const originalShow = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal')
const originalClose = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'close')

beforeEach(() => {
  // jsdom不实现原生模态行为，只模拟开闭；真实焦点约束另由浏览器检查。
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
    configurable: true,
    value: function (this: HTMLDialogElement) {
      this.open = true
    },
  })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', {
    configurable: true,
    value: function (this: HTMLDialogElement) {
      this.open = false
      this.dispatchEvent(new Event('close'))
    },
  })
})
async function mount(history: ReturnType<typeof vi.fn>, viewerId?: string, onDelete = vi.fn()) {
  const refresh = vi.fn()
  host = document.createElement('div')
  document.body.append(host)
  app = createApp({
    render: () =>
      h(ReviewHistory, { restaurantId: '1', item, viewerId, onDelete, onRefresh: refresh }),
  }).use(
    createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: { template: '<p />' } }],
    }),
  )
  app.provide(reviewGatewayKey, {
    ...unavailableReviewGateway,
    history: history as NonNullable<ReviewGateway['history']>,
  })
  app.mount(host)
  await settle()
  return refresh
}
async function toggle(open: boolean) {
  if (open) host.querySelector<HTMLButtonElement>('.history-trigger')!.click()
  else panel().querySelector<HTMLButtonElement>('.close-history')!.click()
  await settle()
}
afterEach(() => {
  app?.unmount()
  host?.remove()
  if (originalShow) Object.defineProperty(HTMLDialogElement.prototype, 'showModal', originalShow)
  else Reflect.deleteProperty(HTMLDialogElement.prototype, 'showModal')
  if (originalClose) Object.defineProperty(HTMLDialogElement.prototype, 'close', originalClose)
  else Reflect.deleteProperty(HTMLDialogElement.prototype, 'close')
})
describe('lazy review history panel', () => {
  it('allows the owner to delete the exact historical record after closing the history dialog', async () => {
    const remove = vi.fn()
    await mount(vi.fn().mockResolvedValue(result('9')), '22', remove)
    await toggle(true)
    button('删除评价').click()
    await settle()
    expect(panel().open).toBe(false)
    expect(remove).toHaveBeenCalledWith(expect.objectContaining({ id: '9', authorId: '22' }))
    expect(document.documentElement.style.overflow).toBe('')
  })
  it('waits for opening, appends more and retains loaded records after reopening', async () => {
    const history = vi
      .fn()
      .mockResolvedValueOnce(result('9', true))
      .mockResolvedValueOnce(result('8'))
    await mount(history)
    expect(history).not.toHaveBeenCalled()
    expect(panel().open).toBe(false)
    await toggle(true)
    expect(history.mock.calls[0]![3]).toBe('start')
    button('加载更多历史点评').click()
    await settle()
    expect(history.mock.calls[1]![3]).toBe('next')
    expect(panel().querySelectorAll('.history-review')).toHaveLength(2)
    await toggle(false)
    await toggle(true)
    expect(history).toHaveBeenCalledTimes(2)
  })
  it('retries the failed cursor without discarding existing records', async () => {
    const history = vi
      .fn()
      .mockRejectedValueOnce(new Error('连接失败'))
      .mockResolvedValueOnce(result())
    await mount(history)
    await toggle(true)
    expect(panel().textContent).toContain('连接失败')
    button('重试历史点评').click()
    await settle()
    expect(history.mock.calls.map((c) => c[3])).toEqual(['start', 'start'])
    expect(panel().querySelectorAll('.history-review')).toHaveLength(1)
  })
  it('closes and refreshes instead of appending after the representative changes', async () => {
    const history = vi.fn().mockResolvedValue({ ...result(), currentReviewId: '11' })
    const refresh = await mount(history)
    await toggle(true)
    expect(refresh).toHaveBeenCalledOnce()
    expect(panel().querySelectorAll('.history-review')).toHaveLength(0)
    expect(panel().open).toBe(false)
  })
  it('aborts closed requests and ignores late results even when transport ignores abort', async () => {
    let resolve!: (page: ReviewHistoryPage) => void
    const history = vi.fn().mockImplementation(
      () =>
        new Promise<ReviewHistoryPage>((r) => {
          resolve = r
        }),
    )
    await mount(history)
    await toggle(true)
    const signal = history.mock.calls[0]![2] as AbortSignal
    await toggle(false)
    expect(signal.aborted).toBe(true)
    resolve(result())
    await settle()
    await toggle(true)
    expect(history).toHaveBeenCalledTimes(2)
    expect(panel().querySelectorAll('.history-review')).toHaveLength(0)
  })
  it('locks background scrolling and returns focus to the trigger on Escape', async () => {
    await mount(vi.fn().mockResolvedValue(result()))
    await toggle(true)
    expect(document.documentElement.style.overflow).toBe('hidden')
    panel().dispatchEvent(new Event('cancel', { cancelable: true }))
    await settle()
    expect(panel().open).toBe(false)
    expect(document.documentElement.style.overflow).toBe('')
    expect(document.activeElement).toBe(host.querySelector('.history-trigger'))
  })
  it('restores scrolling and removes the teleported panel on unmount', async () => {
    await mount(vi.fn().mockResolvedValue(result()))
    await toggle(true)
    app.unmount()
    expect(document.documentElement.style.overflow).toBe('')
    expect(document.querySelector('.history-dialog')).toBeNull()
  })
})
