import { createApp, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AppHeader from './AppHeader.vue'

/** 精简页头只改变入口呈现，保留真实身份、退出事件和等待期间禁用行为。 */
describe('页头账号操作', () => {
  let app: App, host: HTMLDivElement
  afterEach(() => {
    app?.unmount()
    host?.remove()
  })
  async function mount(authenticated = true, loggingOut = false) {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
    })
    await router.push('/')
    const logout = vi.fn()
    host = document.createElement('div')
    document.body.append(host)
    app = createApp(AppHeader, {
      authenticated,
      username: 'kaano',
      discovery: true,
      restoring: false,
      loggingOut,
      hasSession: authenticated,
      error: null,
      onLogout: logout,
    }).use(router)
    app.mount(host)
    return logout
  }
  it('已登录右侧仅有用户名和退出按钮，点击继续向认证层发出退出事件', async () => {
    const logout = await mount()
    const nav = host.querySelector('nav')!
    expect(nav.textContent).toContain('kaano')
    expect(nav.querySelectorAll('a')).toHaveLength(0)
    expect(nav.querySelectorAll('button')).toHaveLength(1)
    expect(host.textContent).not.toMatch(/清水河校区|评分说明|发现美食|地图找店/)
    nav.querySelector('button')!.click()
    expect(logout).toHaveBeenCalledOnce()
  })
  it('未登录只提供登录，不显示虚构用户名或退出操作', async () => {
    await mount(false)
    const nav = host.querySelector('nav')!
    expect(nav.querySelectorAll('a')).toHaveLength(1)
    expect(nav.querySelector('a')?.getAttribute('href')).toBe('/login')
    expect(nav.querySelector('button, .account-name')).toBeNull()
  })
  it('退出过程中禁用按钮，不能重复触发退出', async () => {
    const logout = await mount(true, true)
    const button = host.querySelector<HTMLButtonElement>('nav button')!
    expect(button.disabled).toBe(true)
    expect(button.textContent).toContain('正在退出')
    button.click()
    expect(logout).not.toHaveBeenCalled()
  })
})
