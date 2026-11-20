import { captureLinkTarget } from '@/shared/test/captureLinkTarget'
import { createApp, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearAccessToken, getAccessToken, setAccessToken } from '@/shared/api/authSession'
import { resetPassword, sendPasswordResetCode } from '@/modules/auth/api/authApi'
import { consumeResetLogin } from '@/modules/auth/model/loginPrefill'
import PasswordResetPage from './PasswordResetPage.vue'

vi.mock('@/modules/auth/api/authApi', () => ({
  resetPassword: vi.fn(),
  sendPasswordResetCode: vi.fn(),
}))

/** 挂载真实页面与表单验证两处返回导航，不请求真实账号、邮件或密码重置接口。 */
describe('找回密码页面返回入口', () => {
  let app: App
  let host: HTMLDivElement
  let router: ReturnType<typeof createRouter>

  beforeEach(async () => {
    clearAccessToken()
    consumeResetLogin()
    vi.resetAllMocks()
    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/password-reset', component: PasswordResetPage },
        ...['/login', '/account/settings'].map((path) => ({
          path,
          component: { template: '<p>目标页面</p>' },
        })),
      ],
    })
    await router.push('/password-reset')
    await router.isReady()
    host = document.createElement('div')
    document.body.append(host)
    app = createApp({ render: () => h(RouterView) }).use(router)
    app.mount(host)
    await nextTick()
  })

  afterEach(() => {
    app.unmount()
    host.remove()
    clearAccessToken()
    consumeResetLogin()
  })

  it.each(['.back-link', '.form-footnote a'])(
    '已登录从个人设置进入后，点击%s返回设置并保留会话',
    async (selector) => {
      setAccessToken('old-session')
      await router.push('/account/settings')
      await router.push('/password-reset')
      await nextTick()
      const link = host.querySelector<HTMLAnchorElement>(selector)!
      expect(await captureLinkTarget(app, link)).toBe('/account/settings')
      expect(link.textContent).toContain('返回个人设置')
      link.click()
      await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/account/settings'))
      expect(getAccessToken()).toBe('old-session')
      expect(resetPassword).not.toHaveBeenCalled()
      expect(sendPasswordResetCode).not.toHaveBeenCalled()
    },
  )

  it.each(['.back-link', '.form-footnote a'])('未登录时点击%s返回登录页', async (selector) => {
    const link = host.querySelector<HTMLAnchorElement>(selector)!
    expect(await captureLinkTarget(app, link)).toBe('/login')
    expect(link.textContent).toContain('返回登录')
    link.click()
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/login'))
  })

  it('直接打开页面时按当前会话提供返回入口，会话清除后同步转向登录', async () => {
    setAccessToken('old-session')
    await nextTick()
    for (const selector of ['.back-link', '.form-footnote a']) {
      expect(await captureLinkTarget(app, host.querySelector(selector))).toBe('/account/settings')
    }
    clearAccessToken()
    await nextTick()
    for (const selector of ['.back-link', '.form-footnote a']) {
      expect(await captureLinkTarget(app, host.querySelector(selector))).toBe('/login')
    }
  })

  it('已登录用户完成密码重置后仍清理旧会话并进入登录页', async () => {
    setAccessToken('old-session')
    vi.mocked(resetPassword).mockResolvedValue({ username: 'foodmap_user' })
    for (const [id, value] of Object.entries({
      'reset-email': 'student',
      'reset-code': '012345',
      'reset-password': 'NewPassword2026',
      'reset-confirm-password': 'NewPassword2026',
    })) {
      const input = host.querySelector<HTMLInputElement>(`#${id}`)!
      input.value = value
      input.dispatchEvent(new Event('input', { bubbles: true }))
    }
    host
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/login'))
    expect(resetPassword).toHaveBeenCalledWith({
      email: 'student@std.uestc.edu.cn',
      verificationCode: '012345',
      newPassword: 'NewPassword2026',
    })
    expect(getAccessToken()).toBeNull()
    expect(consumeResetLogin()).toBe('foodmap_user')
  })
})
