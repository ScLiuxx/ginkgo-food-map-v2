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
async function chooseReason(name: string) {
  document.querySelector<HTMLButtonElement>('.reason-trigger')!.click()
  await settle()
  ;[...document.querySelectorAll<HTMLElement>('[role="option"]')]
    .find((item) => item.textContent?.trim() === name)!
    .click()
  await settle()
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

    await chooseReason('辱骂或人身攻击')
    const textarea = document.querySelector<HTMLTextAreaElement>('.report-dialog textarea')!
    textarea.value = '长期辱骂'
    textarea.dispatchEvent(new Event('input'))
    await settle()
    expect(document.querySelector('.report-count, .report-note')).toBeNull()

    document.querySelector<HTMLButtonElement>('.report-dialog button[type="submit"]')!.click()
    await settle()
    expect(submitContentReport).toHaveBeenCalledWith(
      'REVIEW',
      '301',
      { reason: 'ABUSE', detail: '长期辱骂' },
      'session-42',
      expect.any(AbortSignal),
    )
    expect(document.querySelector('.report-success-dialog')?.textContent).toContain('举报成功')
    expect(host.textContent?.trim()).toBe('举报')
    expect(document.querySelector('.report-dialog')).toBeNull()
    document.querySelector<HTMLButtonElement>('[aria-label="关闭举报成功提示"]')!.click()
    await settle()
    expect(document.querySelector('.report-success-dialog')).toBeNull()
    expect(host.textContent?.trim()).toBe('举报')
    expect(document.activeElement).toBe(host.querySelector('.report-entry'))
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
    await chooseReason('广告或垃圾信息')
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
    document.querySelector<HTMLButtonElement>('[aria-label="关闭举报成功提示"]')!.click()
    await settle()

    host.querySelector<HTMLButtonElement>('.report-entry')!.click()
    await settle()
    setAccessToken('session-43')
    await settle()
    expect(document.querySelector('.report-dialog')).toBeNull()
    expect(document.querySelector('.report-success-dialog')).toBeNull()
    app.unmount()
  })
  it('原因下拉用展开状态转动箭头，键盘选择和Esc收起不关闭举报表单', async () => {
    const { app, host } = await mount(
      h(ReportAction, { targetType: 'REPLY', targetId: '501', authenticated: true }),
    )
    host.querySelector<HTMLButtonElement>('.report-entry')!.click()
    await settle()
    const trigger = document.querySelector<HTMLButtonElement>('.reason-trigger')!
    trigger.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true }),
    )
    await settle()
    expect(trigger.getAttribute('aria-expanded')).toBe('true')
    expect(trigger.querySelector('svg')?.classList.contains('expanded')).toBe(true)
    trigger.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
    )
    await settle()
    expect(trigger.getAttribute('aria-expanded')).toBe('false')
    expect(document.querySelector('.report-dialog')).not.toBeNull()
    trigger.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true }),
    )
    await settle()
    trigger.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true }),
    )
    trigger.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }),
    )
    await settle()
    expect(trigger.textContent).toContain('辱骂或人身攻击')
    expect(trigger.querySelector('svg')?.classList.contains('expanded')).toBe(false)
    trigger.click()
    await settle()
    document.querySelector('textarea')!.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    await settle()
    expect(trigger.getAttribute('aria-expanded')).toBe('false')
    app.unmount()
  })
  it('删除静态提示仍校验必选原因和200字上限，说明按Unicode码点计数', async () => {
    const { app, host } = await mount(
      h(ReportAction, { targetType: 'REVIEW', targetId: '301', authenticated: true }),
    )
    host.querySelector<HTMLButtonElement>('.report-entry')!.click()
    await settle()
    const form = document.querySelector('form')!
    form.dispatchEvent(new Event('submit', { cancelable: true }))
    await settle()
    expect(document.querySelector('[role="alert"]')?.textContent).toContain('请选择举报原因')
    expect(submitContentReport).not.toHaveBeenCalled()
    await chooseReason('其他')
    const input = document.querySelector('textarea')!
    input.value = '🥟'.repeat(201)
    input.dispatchEvent(new Event('input'))
    await settle()
    form.dispatchEvent(new Event('submit', { cancelable: true }))
    await settle()
    expect(document.querySelector('[role="alert"]')?.textContent).toContain('最多200字')
    expect(submitContentReport).not.toHaveBeenCalled()
    input.value = '🥟'.repeat(200)
    input.dispatchEvent(new Event('input'))
    await settle()
    form.dispatchEvent(new Event('submit', { cancelable: true }))
    await settle()
    expect(submitContentReport).toHaveBeenCalledOnce()
    app.unmount()
  })
  it('重复举报同样弹成功提示，关闭后入口不附加回执且重开无旧草稿', async () => {
    vi.mocked(submitContentReport).mockResolvedValue({ ...receipt, duplicate: true })
    const { app, host } = await mount(
      h(ReportAction, { targetType: 'REVIEW', targetId: '301', authenticated: true }),
    )
    host.querySelector<HTMLButtonElement>('.report-entry')!.click()
    await settle()
    await chooseReason('无关内容')
    document.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
    await settle()
    expect(document.querySelector('.report-success-dialog')?.textContent).toContain('举报成功')
    document
      .querySelector<HTMLDialogElement>('.report-success-dialog')!
      .dispatchEvent(new Event('cancel', { cancelable: true }))
    await settle()
    expect(host.textContent?.trim()).toBe('举报')
    host.querySelector<HTMLButtonElement>('.report-entry')!.click()
    await settle()
    expect(document.querySelector('.reason-trigger')?.textContent).toContain('请选择举报原因')
    expect(document.querySelector('textarea')!.value).toBe('')
    app.unmount()
  })
  it('在途提交换号后旧回执不弹成功提示，旧信号被取消', async () => {
    let resolve!: (value: ReportReceipt) => void
    vi.mocked(submitContentReport).mockImplementationOnce(
      () =>
        new Promise((r) => {
          resolve = r
        }),
    )
    const { app, host } = await mount(
      h(ReportAction, { targetType: 'REVIEW', targetId: '301', authenticated: true }),
    )
    host.querySelector<HTMLButtonElement>('.report-entry')!.click()
    await settle()
    await chooseReason('其他')
    document.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
    await settle()
    const signal = vi.mocked(submitContentReport).mock.calls[0]![4]!
    setAccessToken('session-43')
    await settle()
    expect(signal.aborted).toBe(true)
    resolve(receipt)
    await settle()
    expect(document.querySelector('.report-dialog, .report-success-dialog')).toBeNull()
    expect(host.textContent?.trim()).toBe('举报')
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
