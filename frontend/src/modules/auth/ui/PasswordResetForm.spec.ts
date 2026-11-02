import { createApp, nextTick } from 'vue'
import type { App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearAccessToken, getAccessToken, setAccessToken } from '@/shared/api/authSession'
import { resetPassword, sendPasswordResetCode } from '../api/authApi'
import PasswordResetForm from './PasswordResetForm.vue'
import { consumeResetLogin } from '../model/loginPrefill'

vi.mock('../api/authApi', () => ({ resetPassword: vi.fn(), sendPasswordResetCode: vi.fn() }))

/** 操作真实Vue表单验证密码重置闭环，不发送真实邮件，也不保存任何真实凭证。 */
describe('password reset form', () => {
  let app: App
  let host: HTMLDivElement
  const router = createRouter({
    history: createMemoryHistory(),
    routes: ['/login', '/password-reset'].map((path) => ({
      path,
      component: { template: '<div />' },
    })),
  })

  beforeEach(async () => {
    clearAccessToken()
    consumeResetLogin()
    vi.resetAllMocks()
    await router.push('/password-reset')
    host = document.createElement('div')
    document.body.append(host)
    app = createApp(PasswordResetForm).use(router)
    app.mount(host)
  })

  afterEach(() => {
    app.unmount()
    host.remove()
    clearAccessToken()
    vi.useRealTimers()
  })

  function fill(values: Partial<Record<string, string>> = {}) {
    const fields = {
      'reset-email': 'Student@STD.UESTC.EDU.CN',
      'reset-code': '012345',
      'reset-password': 'NewPassword2026',
      'reset-confirm-password': 'NewPassword2026',
      ...values,
    }
    for (const [id, value] of Object.entries(fields)) {
      const input = host.querySelector<HTMLInputElement>(`#${id}`)!
      input.value = value!
      input.dispatchEvent(new Event('input', { bubbles: true }))
    }
  }

  async function submit() {
    host
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await nextTick()
  }

  it('submits the dedicated payload and removes old credentials after success', async () => {
    vi.mocked(resetPassword).mockResolvedValue({ username: 'ginkgo_user' })
    setAccessToken('old-session')
    fill()
    await submit()
    await vi.waitFor(() => expect(host.textContent).toContain('密码重置成功'))
    expect(resetPassword).toHaveBeenCalledWith({
      email: 'student@std.uestc.edu.cn',
      verificationCode: '012345',
      newPassword: 'NewPassword2026',
    })
    expect(getAccessToken()).toBeNull()
    expect(localStorage.getItem('ginkgo.access-token')).toBeNull()
    for (const id of ['reset-password', 'reset-confirm-password', 'reset-code']) {
      expect(host.querySelector<HTMLInputElement>(`#${id}`)?.value).toBe('')
    }
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/login'))
    expect(consumeResetLogin()).toBe('ginkgo_user')
    expect(consumeResetLogin()).toBeNull()
  })

  it.each([
    [{ 'reset-email': 'student@example.com' }, '学生邮箱'],
    [{ 'reset-code': '12ab' }, '6位数字'],
    [{ 'reset-password': 'abc' }, '密码至少需要4位'],
    [{ 'reset-password': 'a'.repeat(33) }, '密码不能超过32位'],
    [{ 'reset-password': 'Password2026\u0001' }, '不允许中文或空格'],
    [{ 'reset-confirm-password': 'DifferentPassword2026' }, '两次输入的新密码不一致'],
  ])('rejects invalid input before calling the server: %j', async (fields, message) => {
    fill(fields)
    await submit()
    expect(host.querySelector('[role="alert"]')?.textContent).toContain(message)
    expect(resetPassword).not.toHaveBeenCalled()
  })

  it('shows reset errors, clears passwords and preserves the old login on failure', async () => {
    vi.mocked(resetPassword).mockRejectedValue(new Error('验证码错误或已失效'))
    setAccessToken('old-session')
    fill()
    await submit()
    await vi.waitFor(() =>
      expect(host.querySelector('[role="alert"]')?.textContent).toContain('验证码错误或已失效'),
    )
    expect(getAccessToken()).toBe('old-session')
    expect(host.querySelector<HTMLInputElement>('#reset-password')?.value).toBe('')
    expect(host.querySelector<HTMLInputElement>('#reset-confirm-password')?.value).toBe('')
    expect(host.textContent).not.toContain('密码重置成功')
  })

  it('does not erase a new login created while reset is pending', async () => {
    let finish!: (value: { username: string }) => void
    vi.mocked(resetPassword).mockReturnValue(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    setAccessToken('old-session')
    fill()
    await submit()
    setAccessToken('new-session')
    finish({ username: 'ginkgo_user' })
    await vi.waitFor(() => expect(host.textContent).toContain('密码重置成功'))
    expect(getAccessToken()).toBe('new-session')
  })

  it('accepts code requests generically and prevents resending during the cooldown', async () => {
    vi.useFakeTimers()
    vi.mocked(sendPasswordResetCode).mockResolvedValue()
    fill()
    const send = host.querySelector<HTMLButtonElement>('.code-button')!
    send.click()
    await nextTick()
    await nextTick()
    expect(sendPasswordResetCode).toHaveBeenCalledWith('student@std.uestc.edu.cn')
    expect(host.textContent).toContain('如该邮箱已注册')
    expect(send.textContent).toContain('60 秒后重试')
    expect(send.disabled).toBe(true)
    send.click()
    expect(sendPasswordResetCode).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(60_000)
    expect(send.disabled).toBe(false)
    expect(send.textContent).toContain('发送验证码')
  })

  it('allows retry after code delivery fails without starting a cooldown', async () => {
    vi.mocked(sendPasswordResetCode).mockRejectedValue(new Error('邮件发送失败'))
    fill()
    host.querySelector<HTMLButtonElement>('.code-button')!.click()
    await vi.waitFor(() =>
      expect(host.querySelector('[role="alert"]')?.textContent).toContain('邮件发送失败'),
    )
    expect(host.querySelector<HTMLButtonElement>('.code-button')?.disabled).toBe(false)
    expect(host.textContent).not.toContain('秒后重试')
  })

  it('blocks duplicate submissions while a reset request is pending', async () => {
    let finish!: (value: { username: string }) => void
    vi.mocked(resetPassword).mockReturnValue(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    fill()
    await submit()
    await submit()
    expect(resetPassword).toHaveBeenCalledTimes(1)
    expect(host.querySelector('fieldset')?.disabled).toBe(true)
    finish({ username: 'ginkgo_user' })
    await vi.waitFor(() => expect(host.textContent).toContain('密码重置成功'))
  })
})
