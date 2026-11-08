import { createApp, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { setAccessToken, clearAccessToken } from '@/shared/api/authSession'
import { uploadImage, cancelImage } from '@/modules/file/api/mediaApi'
import { compressPhoto } from '@/modules/file/model/photos'
import {
  fetchWindowPhotos,
  addWindowPhoto,
  removeWindowPhoto,
} from '@/modules/restaurant/api/canteenWindowApi'
import WindowPhotoManagement from './WindowPhotoManagement.vue'

vi.mock('@/modules/file/api/mediaApi', () => ({ uploadImage: vi.fn(), cancelImage: vi.fn() }))
vi.mock('@/modules/file/model/photos', () => ({ compressPhoto: vi.fn() }))
vi.mock('@/modules/restaurant/api/canteenWindowApi', () => ({
  fetchWindowPhotos: vi.fn(),
  addWindowPhoto: vi.fn(),
  removeWindowPhoto: vi.fn(),
}))
const settle = async () => {
  for (let i = 0; i < 20; i++) await nextTick()
}
const photo = { id: '51', url: '/api/canteen-windows/1/photos/51', cover: true }

/** 真实组件验证上传用途、超时幂等重试、删除确认和账号切换，所有网络写入均为夹具。 */
describe('窗口照片管理', () => {
  let app: App, host: HTMLDivElement
  beforeEach(async () => {
    vi.resetAllMocks()
    setAccessToken('admin-one')
    vi.stubGlobal(
      'URL',
      Object.assign(class {}, {
        createObjectURL: vi.fn(() => 'blob:preview'),
        revokeObjectURL: vi.fn(),
      }),
    )
    vi.mocked(fetchWindowPhotos).mockResolvedValue([])
    vi.mocked(uploadImage).mockResolvedValue('51')
    vi.mocked(cancelImage).mockResolvedValue(undefined)
    vi.mocked(compressPhoto).mockImplementation(async (file) => file)
    vi.mocked(addWindowPhoto).mockResolvedValue([photo])
    vi.mocked(removeWindowPhoto).mockResolvedValue(undefined)
    host = document.createElement('div')
    document.body.append(host)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
    })
    await router.push('/admin/restaurants/901/windows')
    app = createApp({ render: () => h(WindowPhotoManagement, { id: '1', name: '测试窗口' }) }).use(
      router,
    )
    app.mount(host)
    await settle()
  })
  afterEach(() => {
    app.unmount()
    host.remove()
    clearAccessToken()
    vi.unstubAllGlobals()
  })
  const button = (text: string) =>
    [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === text)!
  async function choose() {
    const input = host.querySelector<HTMLInputElement>('input[type=file]')!
    Object.defineProperty(input, 'files', {
      configurable: true,
      value: [new File(['image'], 'photo.jpg', { type: 'image/jpeg' })],
    })
    input.dispatchEvent(new Event('change', { bubbles: true }))
    await settle()
  }
  it('使用WINDOW上传，入册成功后释放预览且不删除正式文件', async () => {
    await choose()
    expect(uploadImage).toHaveBeenCalledWith(expect.any(File), 'admin-one', 'WINDOW')
    expect(addWindowPhoto).toHaveBeenCalledWith('1', '51', 'admin-one')
    expect(host.textContent).toContain('封面')
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:preview')
    app.unmount()
    expect(cancelImage).not.toHaveBeenCalled()
  })
  it('入册超时重试原fileId，不重复上传', async () => {
    vi.mocked(addWindowPhoto).mockRejectedValueOnce(new Error('网络超时'))
    await choose()
    expect(host.querySelector('input[type=file]')).toBeNull()
    button('重试保存照片').click()
    await settle()
    expect(uploadImage).toHaveBeenCalledTimes(1)
    expect(vi.mocked(addWindowPhoto).mock.calls).toEqual([
      ['1', '51', 'admin-one'],
      ['1', '51', 'admin-one'],
    ])
    expect(host.textContent).toContain('封面')
  })
  it('删除需确认，失败后可重试；成功后重新读取', async () => {
    await choose()
    vi.mocked(removeWindowPhoto).mockRejectedValueOnce(new Error('删除超时'))
    button('删除照片').click()
    await settle()
    expect(removeWindowPhoto).not.toHaveBeenCalled()
    button('确认删除照片').click()
    await settle()
    expect(host.textContent).toContain('删除超时')
    button('确认删除照片').click()
    await settle()
    expect(removeWindowPhoto).toHaveBeenCalledTimes(2)
    expect(host.textContent).toContain('暂无窗口照片')
  })
  it('换号后的晚到上传不入册，使用原账号取消临时图片', async () => {
    let resolve!: (id: string) => void
    vi.mocked(uploadImage).mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done
        }),
    )
    await choose()
    setAccessToken('another-account')
    resolve('52')
    await settle()
    expect(addWindowPhoto).not.toHaveBeenCalled()
    expect(cancelImage).toHaveBeenCalledWith('52', 'admin-one')
  })
})
