import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { mockDialog } from '@/shared/test/mockDialog'
import PhotoViewer from './PhotoViewer.vue'

let app: App, host: HTMLDivElement, opener: HTMLButtonElement, restore: () => void
let viewer: InstanceType<typeof PhotoViewer>
const photos = [1, 2, 3].map((id) => ({ id: String(id), url: `/api/reviews/10/photos/${id - 1}` }))
const settle = async () => {
  for (let i = 0; i < 8; i++) await nextTick()
}
const dialog = () => document.querySelector<HTMLDialogElement>('.photo-viewer')!
const image = () => dialog().querySelector<HTMLImageElement>('.viewer-image')!
async function click(label: string) {
  dialog().querySelector<HTMLButtonElement>(`[aria-label="${label}"]`)!.click()
  await settle()
}
function pointer(type: string, x: number, y: number, extra: object = {}) {
  const event = new Event(type, { bubbles: true })
  Object.assign(event, {
    clientX: x,
    clientY: y,
    pointerId: 1,
    pointerType: 'touch',
    isPrimary: true,
    button: 0,
    ...extra,
  })
  dialog().querySelector('.photo-stage')!.dispatchEvent(event)
}
beforeEach(() => {
  restore = mockDialog()
  host = document.createElement('div')
  opener = document.createElement('button')
  document.body.append(opener, host)
  opener.focus()
  app = createApp(PhotoViewer)
  viewer = app.mount(host) as InstanceType<typeof PhotoViewer>
})
afterEach(() => {
  app.unmount()
  host.remove()
  opener.remove()
  restore()
  document.documentElement.style.overflow = ''
})
it('从点击的照片打开，按钮、方向键和缩略图切换且首尾不循环', async () => {
  await viewer.open({ photos, index: 1 })
  expect(image().getAttribute('src')).toBe(photos[1]!.url)
  dialog().querySelector<HTMLButtonElement>('[aria-label="下一张"]')!.focus()
  await click('下一张')
  expect(dialog().querySelector('.photo-counter')!.textContent).toContain('3 / 3')
  expect(dialog().querySelector<HTMLButtonElement>('[aria-label="下一张"]')!.disabled).toBe(true)
  expect(document.activeElement).toBe(dialog().querySelector('[aria-label="上一张"]'))
  dialog().dispatchEvent(
    new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true, cancelable: true }),
  )
  await settle()
  expect(image().getAttribute('src')).toBe(photos[1]!.url)
  await click('查看第1张')
  expect(image().getAttribute('src')).toBe(photos[0]!.url)
  expect(dialog().querySelector<HTMLButtonElement>('[aria-label="上一张"]')!.disabled).toBe(true)
})
it('单张隐藏所有切换控件，关闭恢复原焦点及滚动设置', async () => {
  document.documentElement.style.overflow = 'clip'
  await viewer.open({ photos: photos.slice(0, 1), index: 0 })
  expect(dialog().querySelector('.photo-arrow,.photo-filmstrip,.photo-counter')).toBeNull()
  expect(document.documentElement.style.overflow).toBe('hidden')
  dialog().dispatchEvent(new Event('cancel', { bubbles: true, cancelable: true }))
  await settle()
  expect(dialog().open).toBe(false)
  expect(document.activeElement).toBe(opener)
  expect(document.documentElement.style.overflow).toBe('clip')
})
it('只有横向有效手势切换，忽略纵向、短距离及多指手势', async () => {
  await viewer.open({ photos, index: 0 })
  pointer('pointerdown', 250, 180)
  pointer('pointerup', 190, 185)
  await settle()
  expect(image().getAttribute('src')).toBe(photos[1]!.url)
  pointer('pointerdown', 250, 180)
  pointer('pointerup', 240, 300)
  await settle()
  expect(image().getAttribute('src')).toBe(photos[1]!.url)
  pointer('pointerdown', 250, 180)
  pointer('pointerup', 220, 180)
  await settle()
  expect(image().getAttribute('src')).toBe(photos[1]!.url)
  pointer('pointerdown', 250, 180)
  pointer('pointerdown', 240, 180, { isPrimary: false, pointerId: 2 })
  pointer('pointerup', 100, 180)
  await settle()
  expect(image().getAttribute('src')).toBe(photos[1]!.url)
})
it('图片失败不改变照片分组，可以原位重试或直接切换', async () => {
  await viewer.open({ photos, index: 0 })
  const old = image()
  old.dispatchEvent(new Event('error'))
  await settle()
  expect(dialog().textContent).toContain('照片暂时无法显示')
  dialog().querySelector<HTMLButtonElement>('.photo-load-state button')!.click()
  await settle()
  expect(image()).not.toBe(old)
  expect(image().getAttribute('src')).toBe(photos[0]!.url)
  old.dispatchEvent(new Event('error'))
  image().dispatchEvent(new Event('load'))
  await settle()
  expect(dialog().querySelector('.photo-load-state')).toBeNull()
  await click('下一张')
  expect(image().getAttribute('src')).toBe(photos[1]!.url)
})
it('连续重开隔离旧关闭事件和列表，关闭取消尚未执行的打开', async () => {
  await viewer.open({ photos, index: 1 })
  viewer.close()
  await viewer.open({ photos: [{ id: 'new', url: '/new-review-photo' }], index: 0 })
  await settle()
  expect(dialog().open).toBe(true)
  expect(image().getAttribute('src')).toBe('/new-review-photo')
  const pending = viewer.open({ photos, index: 0 })
  viewer.close()
  await pending
  expect(dialog().open).toBe(false)
  expect(document.documentElement.style.overflow).toBe('')
})
it('不重新锁住已被底层详情释放的页面', async () => {
  document.documentElement.style.overflow = 'hidden'
  await viewer.open({ photos, index: 0 })
  document.documentElement.style.overflow = ''
  viewer.close()
  expect(document.documentElement.style.overflow).toBe('')
})
it('卸载会关闭相册并归还自己持有的滚动锁', async () => {
  await viewer.open({ photos, index: 0 })
  app.unmount()
  expect(document.documentElement.style.overflow).toBe('')
})
