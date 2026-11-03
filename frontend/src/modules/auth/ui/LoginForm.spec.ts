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

  it('provides a password reset entry without a remember-me checkbox', () => {
    expect(host.querySelector('.recovery-link a')?.getAttribute('href')).toBe('/password-reset')
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
    await fillAndSubmit('ginkgo_user', 'wrong-password')
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
      user: { id: 42, username: 'ginkgo_user', roles: ['ROLE_USER'] },
    })
    await fillAndSubmit(' ginkgo_user ', ' Ginkgo2026 ')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/'))
    expect(login).toHaveBeenCalledWith({ username: 'ginkgo_user', password: ' Ginkgo2026 ' })
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
      user: { id: 42, username: 'ginkgo_user', roles: ['ROLE_USER'] },
    })
    await fillAndSubmit('ginkgo_user', 'Ginkgo2026')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe(expected))
  })
})
