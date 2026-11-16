import { createApp, h, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, expect, it, vi } from 'vitest'
import { isReturnDestination, useReturnNavigation } from './useReturnNavigation'
import { useListRouteState } from './useListRouteState'
import { waitForPageLayout } from './restorePageScroll'

let app: App | undefined, host: HTMLDivElement | undefined
afterEach(() => {
  app?.unmount()
  app = undefined
  host?.remove()
  host = undefined
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

/** 来路只能指向已知阅读页面，不接受站外地址、表单或登录循环。 */
it.each([
  'https://example.com',
  '//example.com',
  '/\\example.com',
  '/login',
  '/restaurants/submit',
  '/account/contributions/1/edit',
  '/other',
  '/account\n/favorites',
])('拒绝不适合作为返回目标的地址 %s', (value) => expect(isReturnDestination(value)).toBe(false))

it('接受带分页、筛选或锚点的真实列表及详情来路', () => {
  for (const value of [
    '/account/contributions?status=APPROVED&page=2&pageSize=20',
    '/account/favorites?page=3',
    '/users/88?tab=favorites&page=2',
    '/users/88/followers?page=2',
    '/restaurants/19',
    '/canteen-windows/20',
    '/map',
    '/?q=面#list',
  ])
    expect(isReturnDestination(value)).toBe(true)
})

async function mount(path: string, previous?: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<p />' } }],
  })
  await router.push(path)
  router.options.history.replace(
    router.currentRoute.value.fullPath,
    previous ? { back: previous } : {},
  )
  let navigation!: ReturnType<typeof useReturnNavigation>,
    state!: ReturnType<typeof useListRouteState>
  host = document.createElement('div')
  document.body.append(host)
  app = createApp({
    setup() {
      navigation = useReturnNavigation('/account')
      state = useListRouteState(() => router.currentRoute.value.path === '/account/contributions')
      return () =>
        h(
          'a',
          { href: navigation.returnTarget.value, onClick: navigation.returnToPrevious },
          '返回',
        )
    },
  }).use(router)
  app.mount(host)
  return { router, navigation, state }
}

it('正常返回使用原历史条目，修饰键点击保留原生链接行为', async () => {
  const source = '/account/favorites?page=2'
  const { router, navigation } = await mount('/restaurants/19', source)
  const back = vi.spyOn(router, 'back').mockImplementation(() => {})
  expect(navigation.returnTarget.value).toBe(source)
  const modified = new MouseEvent('click', { button: 0, ctrlKey: true, cancelable: true })
  navigation.returnToPrevious(modified)
  expect(modified.defaultPrevented).toBe(false)
  expect(back).not.toHaveBeenCalled()
  const ordinary = new MouseEvent('click', { button: 0, cancelable: true })
  navigation.returnToPrevious(ordinary)
  expect(ordinary.defaultPrevented).toBe(true)
  expect(back).toHaveBeenCalledOnce()
})

it('直达或非法来路使用兜底并替换当前条目，避免返回到原表单', async () => {
  const { router, navigation } = await mount('/restaurants/submit', '//example.com')
  const replace = vi.spyOn(router, 'replace')
  navigation.returnToPrevious(new MouseEvent('click', { button: 0, cancelable: true }))
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/account'))
  expect(replace).toHaveBeenCalledWith('/account')
})

it('列表选项随原地址恢复，替换时保留申请定位与锚点，未启用页面不受影响', async () => {
  const { router, state } = await mount(
    '/account/contributions?status=APPROVED&page=2&pageSize=20&requestId=71#list',
  )
  expect(state.page()).toBe(2)
  expect(state.read('status')).toBe('APPROVED')
  await state.save({ page: 3, pageSize: 50 })
  expect(router.currentRoute.value.query).toEqual({
    status: 'APPROVED',
    page: '3',
    pageSize: '50',
    requestId: '71',
  })
  expect(router.currentRoute.value.hash).toBe('#list')
  await state.save({ page: undefined, status: undefined })
  expect(state.page()).toBe(1)
  await router.push('/restaurants/19?page=3')
  await state.save({ page: 8 })
  expect(router.currentRoute.value.query.page).toBe('3')
  expect(state.page()).toBe(1)
})

it('分页参数不接受数组、负数或超长数值', async () => {
  const { router, state } = await mount('/account/contributions?page=2&page=3')
  expect(state.page()).toBe(1)
  await router.replace('/account/contributions?page=-1')
  expect(state.page()).toBe(1)
  await router.replace('/account/contributions?page=999999999999999999')
  expect(state.page()).toBe(1)
})

it('异步列表读取完成才恢复滚动，再次导航会取消旧恢复', async () => {
  const frames: FrameRequestCallback[] = []
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
    frames.push(callback)
    return frames.length
  })
  host = document.createElement('div')
  host.className = 'account-center'
  host.setAttribute('aria-busy', 'true')
  document.body.append(host)
  let resolved = false
  const pending = waitForPageLayout(() => true).then((value) => {
    resolved = value
  })
  frames.shift()!(0)
  await Promise.resolve()
  expect(resolved).toBe(false)
  host.setAttribute('aria-busy', 'false')
  frames.shift()!(0)
  await pending
  expect(resolved).toBe(true)
  const cancelled = waitForPageLayout(() => false)
  frames.shift()!(0)
  expect(await cancelled).toBe(false)
})
