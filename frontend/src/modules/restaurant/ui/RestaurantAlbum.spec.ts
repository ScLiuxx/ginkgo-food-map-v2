import { createApp, h, nextTick, type App } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  fetchRestaurantAlbum,
  fetchRestaurantAlbumImage,
  type RestaurantAlbumPage,
} from '../api/restaurantAlbumApi'
import RestaurantAlbum from './RestaurantAlbum.vue'

vi.mock('../api/restaurantAlbumApi', () => ({
  fetchRestaurantAlbum: vi.fn(),
  fetchRestaurantAlbumImage: vi.fn(),
}))
const page = (ids = ['11', '12'], nextCursor: string | null = null): RestaurantAlbumPage => ({
  records: ids.map((id, position) => ({
    id,
    position,
    cover: position === 0,
    url: '/unused/' + id,
  })),
  nextCursor,
})
const settle = async () => {
  for (let i = 0; i < 18; i++) await nextTick()
}
let app: App | undefined, host: HTMLDivElement
const visibility = vi.fn()
const showDescriptor = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal')
const closeDescriptor = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'close')
const dialog = () => document.querySelector<HTMLDialogElement>('.photo-viewer')!
const click = async (name: string, root: ParentNode = document) => {
  ;[...root.querySelectorAll<HTMLButtonElement>('button')]
    .find((b) => b.getAttribute('aria-label') === name || b.textContent?.trim() === name)!
    .click()
  await settle()
}
beforeEach(() => {
  vi.resetAllMocks()
  host = document.createElement('div')
  document.body.append(host)
  let serial = 0
  vi.stubGlobal(
    'URL',
    class extends URL {
      static createObjectURL = vi.fn(() => 'blob:album-' + ++serial)
      static revokeObjectURL = vi.fn()
    },
  )
  vi.mocked(fetchRestaurantAlbum).mockResolvedValue(page())
  vi.mocked(fetchRestaurantAlbumImage).mockResolvedValue(new Blob(['jpeg'], { type: 'image/jpeg' }))
  Object.defineProperties(HTMLDialogElement.prototype, {
    showModal: {
      configurable: true,
      value() {
        this.open = true
      },
    },
    close: {
      configurable: true,
      value() {
        this.open = false
      },
    },
  })
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host.remove()
  vi.unstubAllGlobals()
  for (const [name, descriptor] of [
    ['showModal', showDescriptor],
    ['close', closeDescriptor],
  ] as const) {
    if (descriptor) Object.defineProperty(HTMLDialogElement.prototype, name, descriptor)
    else Reflect.deleteProperty(HTMLDialogElement.prototype, name)
  }
})
async function mount(compact = true) {
  app = createApp({
    render: () =>
      h(RestaurantAlbum, {
        restaurantId: '501',
        name: '测试店铺',
        compact,
        onVisibility: visibility,
      }),
  })
  app.mount(host)
  await settle()
}
describe('紧凑商户相册', () => {
  it('商户相册沿用同一深色看图组件，箭头和方向键同步当前选择，单张隐藏控件', async () => {
    await mount()
    await click('查看测试店铺的商户相册大图', host)
    await click('下一张', dialog())
    expect(dialog().querySelector('.photo-counter')?.textContent).toContain('2 / 2')
    expect(host.querySelector('.gallery-main img')?.getAttribute('src')).toBe('blob:album-2')
    dialog().dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true, cancelable: true }),
    )
    await settle()
    expect(dialog().querySelector('.viewer-image')?.getAttribute('src')).toBe('blob:album-1')
    await click('关闭大图', dialog())
    app?.unmount()
    vi.mocked(fetchRestaurantAlbum).mockResolvedValue(page(['11']))
    await mount()
    await click('查看测试店铺的商户相册大图', host)
    expect(dialog().querySelector('.photo-counter,.photo-arrow,.photo-filmstrip')).toBeNull()
  })
  it('大图解码失败沿用相册图片重试，不保留已回收的Blob', async () => {
    await mount()
    await click('查看测试店铺的商户相册大图', host)
    dialog().querySelector('.viewer-image')!.dispatchEvent(new Event('error'))
    await settle()
    expect(dialog().textContent).toContain('照片暂时无法显示')
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:album-1')
    await click('重新加载', dialog())
    expect(fetchRestaurantAlbumImage).toHaveBeenLastCalledWith('501', '11', expect.any(AbortSignal))
    expect(dialog().querySelector('.viewer-image')?.getAttribute('src')).toBe('blob:album-3')
  })
  it('完整模式仍保留页内选图和分页，兼容纠错表单的既有相册', async () => {
    vi.mocked(fetchRestaurantAlbum).mockResolvedValueOnce(page(['11', '12'], '12'))
    await mount(false)
    expect(host.querySelectorAll('.gallery-thumbnails button')).toHaveLength(2)
    expect(host.textContent).toContain('加载更多照片')
    await click('选择商户照片 2', host)
    expect(host.querySelector('.gallery-main img')?.getAttribute('src')).toBe('blob:album-2')
    expect(dialog().querySelector('.gallery-thumbnails')).toBeNull()
  })
  it('仅成功空列表收起，既有完整相册仍显示空态', async () => {
    vi.mocked(fetchRestaurantAlbum).mockResolvedValue(page([]))
    await mount()
    expect(host.querySelector<HTMLElement>('.restaurant-album')?.style.display).toBe('none')
    expect(visibility.mock.calls.map(([value]) => value)).toEqual([true, false])
    app?.unmount()
    await mount(false)
    expect(host.querySelector<HTMLElement>('.restaurant-album')?.style.display).not.toBe('none')
    expect(host.textContent).toContain('暂无商户照片')
  })
  it('读取失败保留重试，确认读到空列表后才通知详情收起', async () => {
    vi.mocked(fetchRestaurantAlbum)
      .mockRejectedValueOnce(new Error('读取中断'))
      .mockResolvedValueOnce(page([]))
    await mount()
    expect(host.querySelector<HTMLElement>('.restaurant-album')?.style.display).not.toBe('none')
    expect(host.textContent).toContain('读取中断')
    expect(visibility).not.toHaveBeenCalledWith(false)
    await click('重试相册')
    expect(visibility).toHaveBeenLastCalledWith(false)
  })
  it('页首只留封面，大图中选图及失败分页重试不丢选择、不伪造总数', async () => {
    vi.mocked(fetchRestaurantAlbum).mockResolvedValueOnce(page(['11', '12'], '12'))
    await mount()
    expect(host.textContent).toContain('2+ 张照片')
    expect(host.querySelector('.gallery-thumbnails')).toBeNull()
    await click('查看测试店铺的商户相册大图', host)
    expect(dialog().open).toBe(true)
    await click('查看第2张', dialog())
    expect(host.querySelector('.gallery-main img')?.getAttribute('src')).toBe('blob:album-2')
    vi.mocked(fetchRestaurantAlbum).mockRejectedValueOnce(new Error('分页中断'))
    await click('加载更多照片', dialog())
    expect(dialog().textContent).toContain('分页中断')
    expect(dialog().querySelectorAll('.photo-thumb')).toHaveLength(2)
    vi.mocked(fetchRestaurantAlbum).mockResolvedValueOnce({
      records: [{ id: '13', position: 2, cover: false, url: '/unused/13' }],
      nextCursor: null,
    })
    dialog().querySelector<HTMLButtonElement>('[aria-label="重试加载更多"]')!.focus()
    await click('重试加载更多', dialog())
    expect(fetchRestaurantAlbum).toHaveBeenLastCalledWith('501', '12', expect.any(AbortSignal))
    expect(host.textContent).toContain('3 张照片')
    expect(dialog().querySelectorAll('.photo-thumb')).toHaveLength(3)
    expect(document.activeElement).toBe(dialog().querySelector('.photo-thumb[aria-current="true"]'))
    expect(host.querySelector('.gallery-main img')?.getAttribute('src')).toBe('blob:album-2')
    await click('关闭大图', dialog())
    expect(dialog().open).toBe(false)
    expect(URL.revokeObjectURL).not.toHaveBeenCalled()
  })
  it('封面损坏仍可重试或从大图选择其他照片', async () => {
    await mount()
    host.querySelector('img')!.dispatchEvent(new Event('error'))
    await settle()
    expect(host.textContent).toContain('图片无法显示')
    expect(visibility).not.toHaveBeenCalledWith(false)
    await click('查看其他照片', host)
    await click('查看第2张', dialog())
    expect(host.querySelector('.gallery-main img')?.getAttribute('src')).toBe('blob:album-2')
    expect(fetchRestaurantAlbum).toHaveBeenCalledTimes(1)
  })
})
