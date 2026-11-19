import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { mockDialog } from '@/shared/test/mockDialog'
import LeaveConfirmation from './LeaveConfirmation.vue'

describe('离开确认', () => {
  let app: App | undefined, host: HTMLDivElement, restore: () => void
  let component: InstanceType<typeof LeaveConfirmation>
  beforeEach(() => {
    restore = mockDialog()
    host = document.createElement('div')
    document.body.append(host)
    app = createApp(LeaveConfirmation, { message: '资料尚未提交，确定离开？' })
    component = app.mount(host) as InstanceType<typeof LeaveConfirmation>
  })
  afterEach(() => {
    app?.unmount()
    host.remove()
    restore()
  })
  const dialog = () => document.querySelector<HTMLDialogElement>('.leave-confirmation')!
  it('继续编辑和Esc取消离开，确认后才允许离开', async () => {
    const first = component.ask()
    expect(dialog().open).toBe(true)
    dialog().querySelector<HTMLButtonElement>('button')!.click()
    expect(await first).toBe(false)
    const second = component.ask()
    dialog().dispatchEvent(new Event('cancel', { cancelable: true }))
    expect(await second).toBe(false)
    const third = component.ask()
    dialog().querySelector<HTMLButtonElement>('.leave')!.click()
    expect(await third).toBe(true)
    expect(dialog().open).toBe(false)
  })
  it('新导航取消旧等待，旧close事件不能取消新确认', async () => {
    const previous = component.ask()
    const latest = component.ask()
    expect(await previous).toBe(false)
    await nextTick()
    expect(dialog().open).toBe(true)
    dialog().querySelector<HTMLButtonElement>('.leave')!.click()
    expect(await latest).toBe(true)
  })
  it('卸载时取消等待且移除模态层', async () => {
    const pending = component.ask()
    app!.unmount()
    app = undefined
    expect(await pending).toBe(false)
    expect(document.querySelector('.leave-confirmation')).toBeNull()
  })
})
