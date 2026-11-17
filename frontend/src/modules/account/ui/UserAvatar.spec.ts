import { createApp, h, nextTick, type App } from 'vue'
import { afterEach, expect, it } from 'vitest'
import UserAvatar from './UserAvatar.vue'
import { avatarUpdated } from '../model/avatar'

let app: App, host: HTMLDivElement
afterEach(() => {
  app.unmount()
  host.remove()
})
function mount(userId = '77', preview = false) {
  host = document.createElement('div')
  app = createApp({ render: () => h(UserAvatar, { userId, username: '同学', preview }) })
  app.mount(host)
}
it('初始首字占位，读到图片才显示；读图失败恢复首字', async () => {
  mount()
  expect(host.textContent).toBe('同')
  host.querySelector('img')!.dispatchEvent(new Event('load'))
  await nextTick()
  expect(host.textContent).toBe('')
  host.querySelector('img')!.dispatchEvent(new Event('error'))
  await nextTick()
  expect(host.textContent).toBe('同')
  expect(host.querySelector('img')).toBeNull()
})
it('同一用户保存后自动重新读图，其他用户版本不触发重读', async () => {
  mount()
  const source = host.querySelector('img')!.getAttribute('src')
  avatarUpdated('88')
  await nextTick()
  expect(host.querySelector('img')!.getAttribute('src')).toBe(source)
  avatarUpdated('77')
  await nextTick()
  expect(host.querySelector('img')!.getAttribute('src')).not.toBe(source)
})
it('非法ID及演示预览不产生图片请求', () => {
  mount('https://example.test/avatar.jpg')
  expect(host.querySelector('img')).toBeNull()
  app.unmount()
  mount('77', true)
  expect(host.querySelector('img')).toBeNull()
})
