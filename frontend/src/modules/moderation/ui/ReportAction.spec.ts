import { createApp, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearAccessToken, setAccessToken } from '@/shared/api/authSession'
import { submitContentReport, type ReportReceipt } from '../api/contentReportsApi'
import ReportAction from './ReportAction.vue'
import ContentActions from '@/modules/community/ui/ContentActions.vue'

vi.mock('../api/contentReportsApi', () => ({ submitContentReport: vi.fn() }))
vi.mock('@/modules/community/ui/LikeButton.vue', () => ({
  default: { template: '<span class="like-stub" />' },
}))
const receipt: ReportReceipt = {
  reportId: '11',
  targetType: 'REVIEW',
  targetId: '301',
  status: 'PENDING',
  duplicate: false,
  createdAt: '2026-11-10T01:00:00Z',
}
const settle = async () => {
  for (let i = 0; i < 20; i++) await nextTick()
}
let router: Router | undefined
async function makeRouter() {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/restaurants/1', component: { template: '<p>店铺</p>' } },
      { path: '/login', component: { template: '<p>登录</p>' } },
    ],
  })
  await router.push('/restaurants/1')
  await router.isReady()
  return router
}
async function mount(target: ReturnType<typeof h>): Promise<{ app: App; host: HTMLDivElement }> {
  const host = document.createElement('div')
  document.body.append(host)
  const app = createApp({ render: () => target })
  app.use(await makeRouter())
  app.mount(host)
  await settle()
  return { app, host }
}
beforeEach(() => {
  clearAccessToken()
  setAccessToken('session-42')
  vi.mocked(submitContentReport).mockReset().mockResolvedValue(receipt)
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
    configurable: true,
    value() {
      this.open = true
    },
  })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', {
    configurable: true,
    value() {
      this.open = false
    },
  })
})
afterEach(() => {
  clearAccessToken()
  document.body.innerHTML = ''
  delete (HTMLDialogElement.prototype as unknown as Record<string, unknown>).showModal
  delete (HTMLDialogElement.prototype as unknown as Record<string, unknown>).close
})

/** 弹窗内原因必选、说明200字上限，成功后关闭并给出明确回执。 */
describe('举报弹窗', () => {
  it('提交点评举报后显示成功提示并关闭', async () => {
    const { app, host } = await mount(
      h(ReportAction, { targetType: 'REVIEW', targetId: '301', authenticated: true }),
    )
    host.querySelector<HTMLButtonElement>('.report-entry')!.click()
    await settle()
    expect(document.querySelector('.report-dialog')).not.toBeNull()

    const select = document.querySelector<HTMLSelectElement>('.report-dialog select')!
    select.value = 'ABUSE'
    select.dispatchEvent(new Event('change'))
    const textarea = document.querySelector<HTMLTextAreaElement>('.report-dialog textarea')!
    textarea.value = '长期辱骂'
    textarea.dispatchEvent(new Event('input'))
    await settle()
    expect(document.querySelector('.report-count')?.textContent).toContain('4 / 200')

    document.querySelector<HTMLButtonElement>('.report-dialog button[type="submit"]')!.click()
    await settle()
    expect(submitContentReport).toHaveBeenCalledWith(
      'REVIEW',
      '301',
      { reason: 'ABUSE', detail: '长期辱骂' },
      'session-42',
      expect.any(AbortSignal),
    )
    expect(host.textContent).toContain('已提交举报')
    expect(document.querySelector('.report-dialog')).toBeNull()
    app.unmount()
  })

  it('失败保留草稿可重试，换号清理弹窗与提示', async () => {
    vi.mocked(submitContentReport)
      .mockRejectedValueOnce(new Error('服务暂时不可用'))
      .mockResolvedValueOnce(receipt)
    const { app, host } = await mount(
      h(ReportAction, { targetType: 'REPLY', targetId: '501', authenticated: true }),
    )
    host.querySelector<HTMLButtonElement>('.report-entry')!.click()
    await settle()
    const select = document.querySelector<HTMLSelectElement>('.report-dialog select')!
    select.value = 'SPAM'
    select.dispatchEvent(new Event('change'))
    await settle()
    document.querySelector<HTMLButtonElement>('.report-dialog button[type="submit"]')!.click()
    await settle()
    expect(document.querySelector('.report-dialog')?.textContent).toContain('服务暂时不可用')
    expect(document.querySelector<HTMLTextAreaElement>('.report-dialog textarea')?.disabled).toBe(
      false,
    )
    document.querySelector<HTMLButtonElement>('.report-dialog button[type="submit"]')!.click()
    await settle()
    expect(submitContentReport).toHaveBeenCalledTimes(2)
    expect(document.querySelector('.report-dialog')).toBeNull()

    host.querySelector<HTMLButtonElement>('.report-entry')!.click()
    await settle()
    setAccessToken('session-43')
    await settle()
    expect(document.querySelector('.report-dialog')).toBeNull()
    expect(host.textContent).not.toContain('已提交举报')
    app.unmount()
  })
})

/** 自己的内容由操作区隐藏举报入口，真实授权仍由服务端判定。 */
describe('举报入口显隐', () => {
  it('本人内容不显示举报，他人内容显示', async () => {
    const own = await mount(
      h(ContentActions, {
        own: true,
        authenticated: true,
        kind: 'review',
        viewerId: '42',
        targetId: '301',
      }),
    )
    expect(own.host.querySelector('.report-entry')).toBeNull()
    own.app.unmount()

    const other = await mount(
      h(ContentActions, {
        own: false,
        authenticated: true,
        kind: 'review',
        viewerId: '42',
        targetId: '301',
      }),
    )
    expect(other.host.querySelector('.report-entry')?.textContent).toContain('举报')
    other.app.unmount()
  })
})
