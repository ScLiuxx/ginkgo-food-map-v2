import { captureLinkTarget } from '@/shared/test/captureLinkTarget'
import { createApp, nextTick } from 'vue'
import type { App } from 'vue'
import { createPinia, disposePinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearAccessToken } from '@/shared/api/authSession'
import { login } from '../api/authApi'
import LoginForm from './LoginForm.vue'
import { prepareResetLogin } from '../model/loginPrefill'

vi.mock('../api/authApi', () => ({ login: vi.fn(), fetchCurrentUser: vi.fn(), logout: vi.fn() }))

/** 在 DOM 中操作真实 Vue 表单，验证输入校验、失败反馈及成功跳转。 */
describe('login form', () => {
  it('routes administrator accounts to the workbench instead of ordinary-user return targets', async () => {
    await router.replace('/login?redirect=/restaurants/new')
    vi.mocked(login).mockResolvedValue({
      accessToken: 'test-admin-session',
      tokenType: 'Bearer',
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id: 99, username: 'manager', roles: ['ROLE_USER', 'ROLE_ADMIN'] },
    })
    await fillAndSubmit('manager', 'secret-password')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/admin/reviews'))
    expect(host.querySelector<HTMLInputElement>('#login-password')?.value).toBe('')
  })
  let app: App
  let host: HTMLDivElement
  let pinia: ReturnType<typeof createPinia>
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      '/',
      '/login',
      '/register',
      '/password-reset',
      '/restaurants/new',
      '/admin/reviews',
    ].map((path) => ({
      path,
      component: { template: '<div />' },
    })),
  })

  beforeEach(async () => {
    clearAccessToken()
    vi.resetAllMocks()
    pinia = createPinia()
    setActivePinia(pinia)
    await router.push('/login')
    host = document.createElement('div')
    document.body.append(host)
    app = createApp(LoginForm).use(pinia).use(router)
    app.mount(host)
  })

  afterEach(() => {
    app.unmount()
    disposePinia(pinia)
    host.remove()
    clearAccessToken()
  })

  async function fillAndSubmit(username: string, password: string) {
    for (const [id, value] of [
      ['login-username', username],
      ['login-password', password],
    ]) {
      const input = host.querySelector<HTMLInputElement>(`#${id}`)!
      input.value = value!
      input.dispatchEvent(new Event('input', { bubbles: true }))
    }
    host
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await nextTick()
  }

  it('rejects empty input without sending a request', async () => {
    await fillAndSubmit('', '')
    expect(host.querySelector('#login-username-error')?.textContent).toContain('请输入用户名')
    expect(host.querySelector('#login-password-error')?.textContent).toContain('请输入密码')
    expect(login).not.toHaveBeenCalled()
  })

  it('provides a password reset entry without a remember-me checkbox', async () => {
    expect(await captureLinkTarget(app, host.querySelector('.recovery-link a'))).toBe(
      '/password-reset',
    )
    expect(host.querySelector('input[type="checkbox"]')).toBeNull()
  })

  it('prefills only the verified username after resetting the password', async () => {
    app.unmount()
    prepareResetLogin('Ka')
    app = createApp(LoginForm).use(pinia).use(router)
    app.mount(host)
    expect(host.querySelector<HTMLInputElement>('#login-username')?.value).toBe('Ka')
    expect(host.querySelector<HTMLInputElement>('#login-password')?.value).toBe('')
    expect(host.querySelector('[role="status"]')?.textContent).toContain('密码已重置')
    expect(login).not.toHaveBeenCalled()
  })

  it('shows a server error and clears the password after failure', async () => {
    vi.mocked(login).mockRejectedValue(new Error('用户名或密码错误'))
    await fillAndSubmit('foodmap_user', 'wrong-password')
    await vi.waitFor(() => {
      expect(host.querySelector('[role="alert"]')?.textContent).toContain('用户名或密码错误')
      expect(host.querySelector<HTMLInputElement>('#login-password')?.value).toBe('')
    })
    expect(router.currentRoute.value.path).toBe('/login')
  })

  it('keeps password spaces intact and returns home after successful login', async () => {
    vi.mocked(login).mockResolvedValue({
      accessToken: 'test-session',
      tokenType: 'Bearer',
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id: 42, username: 'foodmap_user', roles: ['ROLE_USER'] },
    })
    await fillAndSubmit(' foodmap_user ', ' Foodmap2026 ')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/'))
    expect(login).toHaveBeenCalledWith({ username: 'foodmap_user', password: ' Foodmap2026 ' })
    expect(host.querySelector<HTMLInputElement>('#login-password')?.value).toBe('')
  })

  async function switchMode(mode: 'username' | 'student') {
    host.querySelector<HTMLButtonElement>(`#login-tab-${mode}`)!.click()
    await nextTick()
  }

  it('uses the selected student number as a campus email without changing password spaces', async () => {
    vi.mocked(login).mockResolvedValue({
      accessToken: 'email-session',
      tokenType: 'Bearer',
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id: 42, username: 'foodmap_user', roles: ['ROLE_USER'] },
    })
    await switchMode('student')
    await fillAndSubmit(' 001234567890 ', ' secret ')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/'))
    expect(login).toHaveBeenCalledWith({
      username: '001234567890@std.uestc.edu.cn',
      password: ' secret ',
    })
    expect(host.querySelector<HTMLInputElement>('#login-username')?.inputMode).toBe('numeric')
    expect(host.querySelector('#login-email-suffix')?.textContent).toBe('@std.uestc.edu.cn')
  })

  it('keeps a numeric username as a username instead of guessing the login method', async () => {
    vi.mocked(login).mockRejectedValue(new Error('用户名或密码错误'))
    await fillAndSubmit('001234567890', 'secret')
    expect(login).toHaveBeenCalledWith({ username: '001234567890', password: 'secret' })
    expect(host.querySelector('#login-email-suffix')).toBeNull()
  })

  it.each(['', 'student', '123 456', '１２３４５', '123@std.uestc.edu.cn', '1e12'])(
    'rejects invalid student number %j before login',
    async (value) => {
      await switchMode('student')
      await fillAndSubmit(value, 'secret')
      expect(host.querySelector('#login-username-error')?.textContent).toBeTruthy()
      expect(login).not.toHaveBeenCalled()
    },
  )

  it('rejects email input in username mode rather than selecting an email account silently', async () => {
    await fillAndSubmit('student@std.uestc.edu.cn', 'secret')
    expect(host.querySelector('#login-username-error')?.textContent).toContain('切换至学号登录')
    expect(login).not.toHaveBeenCalled()
  })

  it('preserves independent account drafts and clears password and errors when switching', async () => {
    await fillAndSubmit('', 'secret')
    expect(host.querySelector('#login-username-error')).not.toBeNull()
    const account = host.querySelector<HTMLInputElement>('#login-username')!
    account.value = 'my_username'
    account.dispatchEvent(new Event('input', { bubbles: true }))
    await switchMode('student')
    expect(account.value).toBe('')
    expect(host.querySelector<HTMLInputElement>('#login-password')?.value).toBe('')
    expect(host.querySelector('#login-username-error')).toBeNull()
    account.value = '001234567890'
    account.dispatchEvent(new Event('input', { bubbles: true }))
    await switchMode('username')
    expect(account.value).toBe('my_username')
    await switchMode('student')
    expect(account.value).toBe('001234567890')
  })

  it('supports keyboard tab navigation without submitting the form', async () => {
    const username = host.querySelector<HTMLButtonElement>('#login-tab-username')!
    username.focus()
    username.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }))
    await nextTick()
    await nextTick()
    const student = host.querySelector<HTMLButtonElement>('#login-tab-student')!
    expect(student.getAttribute('aria-selected')).toBe('true')
    expect(document.activeElement).toBe(student)
    student.dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true }))
    await nextTick()
    expect(username.getAttribute('aria-selected')).toBe('true')
    expect(login).not.toHaveBeenCalled()
  })

  it('locks mode switching while the submitted identity is pending', async () => {
    let fail!: (reason: Error) => void
    vi.mocked(login).mockReturnValue(
      new Promise((_resolve, reject) => {
        fail = reject
      }),
    )
    await switchMode('student')
    await fillAndSubmit('001234567890', 'secret')
    const username = host.querySelector<HTMLButtonElement>('#login-tab-username')!
    expect(username.disabled).toBe(true)
    username.click()
    expect(host.querySelector('#login-tab-student')?.getAttribute('aria-selected')).toBe('true')
    fail(new Error('密码错误'))
    await vi.waitFor(() => expect(username.disabled).toBe(false))
    expect(login).toHaveBeenCalledTimes(1)
    expect(host.querySelector<HTMLInputElement>('#login-password')?.value).toBe('')
  })

  it.each([
    ['/restaurants/new', '/restaurants/new'],
    ['https://untrusted.example', '/'],
    ['//untrusted.example', '/'],
  ])('limits post-login return destinations: %s', async (redirect, expected) => {
    await router.push({ path: '/login', query: { redirect } })
    vi.mocked(login).mockResolvedValue({
      accessToken: 'test-return-session',
      tokenType: 'Bearer',
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id: 42, username: 'foodmap_user', roles: ['ROLE_USER'] },
    })
    await fillAndSubmit('foodmap_user', 'Foodmap2026')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe(expected))
  })
})
