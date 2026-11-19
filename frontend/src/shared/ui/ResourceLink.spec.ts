import { createApp, h, type App } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import ResourceLink from './ResourceLink.vue'

let app: App | undefined
let host: HTMLDivElement
afterEach(() => {
  app?.unmount()
  host?.remove()
  vi.unstubAllGlobals()
})
function mount(href: string, target: '_blank' | '_self' = '_blank') {
  host = document.createElement('div')
  document.body.append(host)
  app = createApp({ render: () => h(ResourceLink, { href, target }, () => '导航') })
  app.mount(host)
  return host.querySelector('a')!
}
it.each(['https://uri.amap.com/navigation?to=104,30,店铺', '/api/restaurants/42/photos/8'])(
  '资源%s不输出href，仍在新标签打开且隔离opener',
  (href) => {
    const open = vi.fn()
    vi.stubGlobal('open', open)
    const link = mount(href)
    expect(link.hasAttribute('href')).toBe(false)
    link.click()
    expect(open).toHaveBeenCalledExactlyOnceWith(
      new URL(href, location.href).href,
      '_blank',
      'noopener,noreferrer',
    )
  },
)
it('原页面重新打开继续使用完整重载，键盘入口与点击一致', () => {
  const assign = vi.fn()
  vi.stubGlobal('location', { href: 'http://localhost/restaurants/new', assign })
  const link = mount('/restaurants/new', '_self')
  link.dispatchEvent(
    new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }),
  )
  expect(assign).toHaveBeenCalledExactlyOnceWith('http://localhost/restaurants/new')
})
it('不会执行资源地址中的脚本协议', () => {
  const open = vi.fn()
  vi.stubGlobal('open', open)
  mount('javascript:alert(1)').click()
  expect(open).not.toHaveBeenCalled()
})
