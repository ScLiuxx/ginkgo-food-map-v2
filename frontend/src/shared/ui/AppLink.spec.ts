import { createApp, h, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, expect, it, vi } from 'vitest'
import AppLink from './AppLink.vue'

let app: App | undefined
let host: HTMLDivElement
afterEach(() => {
  app?.unmount()
  host?.remove()
})
async function mount(attributes: Record<string, unknown> = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<p />' } }],
  })
  await router.push('/account?page=2')
  host = document.createElement('div')
  document.body.append(host)
  app = createApp({ render: () => h(AppLink, { to: '/map', ...attributes }, () => '地图') }).use(
    router,
  )
  app.mount(host)
  return router
}
it('保留链接语义及键盘入口，不生成href；点击保留查询、锚点和历史返回', async () => {
  const router = await mount({
    to: { path: '/map', query: { q: '面馆' }, hash: '#list' },
    title: '查看地图',
    class: 'old-style',
  })
  const link = host.querySelector('a')!
  expect(link.hasAttribute('href')).toBe(false)
  expect(link.getAttribute('role')).toBe('link')
  expect(link.tabIndex).toBe(0)
  expect(link.title).toBe('查看地图')
  expect(link.classList.contains('old-style')).toBe(true)
  link.click()
  await vi.waitFor(() =>
    expect(router.currentRoute.value).toMatchObject({
      path: '/map',
      query: { q: '面馆' },
      hash: '#list',
    }),
  )
  expect(link.getAttribute('aria-current')).toBe('page')
  expect(link.classList.contains('router-link-active')).toBe(true)
  router.back()
  await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/account?page=2'))
})
it('Enter复用鼠标点击的捕获处理，来源返回可以阻止默认跳转', async () => {
  const capture = vi.fn((event: MouseEvent) => event.preventDefault())
  const router = await mount({ onClickCapture: capture })
  host
    .querySelector('a')!
    .dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }))
  await vi.waitFor(() => expect(capture).toHaveBeenCalledOnce())
  expect(router.currentRoute.value.fullPath).toBe('/account?page=2')
})
it('Enter可进入目标页，replace不增加临时写评历史', async () => {
  const router = await mount({ to: '/account?page=2&writeReview=1', replace: true })
  await router.push('/map')
  const link = host.querySelector('a')!
  link.dispatchEvent(
    new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }),
  )
  await vi.waitFor(() =>
    expect(router.currentRoute.value.fullPath).toBe('/account?page=2&writeReview=1'),
  )
  router.back()
  await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/account?page=2'))
})
it('aria-disabled阻止鼠标与Enter跳转，路由守卫继续生效', async () => {
  const router = await mount({ 'aria-disabled': 'true' })
  const guard = vi.fn(() => false)
  router.beforeEach(guard)
  const link = host.querySelector('a')!
  link.click()
  link.dispatchEvent(
    new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }),
  )
  await Promise.resolve()
  expect(guard).not.toHaveBeenCalled()
  expect(router.currentRoute.value.path).toBe('/account')
})
it('正常入口仍服从路由守卫，取消导航不切换选中状态', async () => {
  const router = await mount()
  router.beforeEach(() => false)
  host.querySelector('a')!.click()
  await Promise.resolve()
  await Promise.resolve()
  expect(router.currentRoute.value.path).toBe('/account')
  expect(host.querySelector('a')!.hasAttribute('aria-current')).toBe(false)
})
