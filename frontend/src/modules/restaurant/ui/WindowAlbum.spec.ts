import { createApp, h, nextTick, type App } from 'vue'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { fetchWindowPhotos } from '../api/canteenWindowApi'
import WindowAlbum from './WindowAlbum.vue'

vi.mock('../api/canteenWindowApi', () => ({ fetchWindowPhotos: vi.fn() }))
const settle = async () => {
  for (let i = 0; i < 15; i++) await nextTick()
}

/** 窗口相册独立验证读取失败、图片重试和真实空态，不使用点评图片填补封面。 */
describe('窗口相册', () => {
  let app: App, host: HTMLDivElement
  beforeEach(() => {
    vi.resetAllMocks()
    host = document.createElement('div')
    document.body.append(host)
  })
  afterEach(() => {
    app?.unmount()
    host.remove()
  })
  async function mount() {
    app = createApp({ render: () => h(WindowAlbum, { id: '1', name: '测试窗口' }) })
    app.mount(host)
    await settle()
  }
  it('空相册显示暂无窗口照片', async () => {
    vi.mocked(fetchWindowPhotos).mockResolvedValue([])
    await mount()
    expect(host.textContent).toContain('暂无窗口照片')
    expect(host.querySelector('img')).toBeNull()
  })
  it('读取失败可重试并选择相册照片', async () => {
    vi.mocked(fetchWindowPhotos)
      .mockRejectedValueOnce(new Error('暂时不可用'))
      .mockResolvedValueOnce([
        { id: '1', url: '/photo/1', cover: true },
        { id: '2', url: '/photo/2', cover: false },
      ])
    await mount()
    expect(host.textContent).toContain('窗口相册暂时无法读取')
    host.querySelector('button')!.click()
    await settle()
    host.querySelector<HTMLButtonElement>('[aria-label="选择窗口照片 2"]')!.click()
    await settle()
    expect(host.querySelector('.gallery-main img')?.getAttribute('src')).toContain('/photo/2')
  })
  it('损坏图片显示重试入口，重试不重新请求相册列表', async () => {
    vi.mocked(fetchWindowPhotos).mockResolvedValue([{ id: '1', url: '/photo/1', cover: true }])
    await mount()
    host.querySelector('img')!.dispatchEvent(new Event('error'))
    await settle()
    expect(host.textContent).toContain('这张照片暂时无法显示')
    host.querySelector<HTMLButtonElement>('.gallery-empty button')!.click()
    await settle()
    expect(host.querySelector('.gallery-main img')?.getAttribute('src')).toContain('retry=1')
    expect(fetchWindowPhotos).toHaveBeenCalledTimes(1)
  })
})
