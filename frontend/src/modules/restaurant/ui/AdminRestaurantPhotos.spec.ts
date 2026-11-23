import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { mockDialog } from '@/shared/test/mockDialog'
import { setAccessToken, clearAccessToken } from '@/shared/api/authSession'
import { fetchRestaurantAlbum, fetchRestaurantAlbumImage } from '../api/restaurantAlbumApi'
import { fetchReviewPhotoCandidates, readReviewPhoto } from '../api/restaurantPhotoManagementApi'
import { uploadImage, cancelImage } from '@/modules/file/api/mediaApi'
import { compressPhoto } from '@/modules/file/model/photos'
import AdminRestaurantPhotos from './AdminRestaurantPhotos.vue'

vi.mock('../api/restaurantAlbumApi', () => ({
  fetchRestaurantAlbum: vi.fn(),
  fetchRestaurantAlbumImage: vi.fn(),
}))
vi.mock('../api/restaurantPhotoManagementApi', () => ({
  fetchReviewPhotoCandidates: vi.fn(),
  readReviewPhoto: vi.fn(),
}))
vi.mock('@/modules/file/api/mediaApi', () => ({ uploadImage: vi.fn(), cancelImage: vi.fn() }))
vi.mock('@/modules/file/model/photos', () => ({ compressPhoto: vi.fn() }))
let app: App, host: HTMLDivElement, restore: () => void
const selection = vi.fn(),
  state = vi.fn()
const settle = async () => {
  for (let i = 0; i < 18; i++) await nextTick()
}
beforeEach(() => {
  vi.resetAllMocks()
  setAccessToken('admin-photo-session')
  restore = mockDialog()
  const BaseURL = URL
  let serial = 0
  vi.stubGlobal(
    'URL',
    Object.assign(class extends BaseURL {}, {
      createObjectURL: vi.fn(() => `blob:photo-${++serial}`),
      revokeObjectURL: vi.fn(),
    }),
  )
  vi.mocked(fetchRestaurantAlbum).mockResolvedValue({
    records: [1, 2].map((id) => ({
      id: String(id),
      position: id - 1,
      cover: id === 1,
      url: `/api/restaurants/901/photos/${id}`,
    })),
    nextCursor: null,
  })
  vi.mocked(fetchRestaurantAlbumImage).mockResolvedValue(new Blob(['jpeg'], { type: 'image/jpeg' }))
  vi.mocked(uploadImage).mockResolvedValue('501')
  vi.mocked(cancelImage).mockResolvedValue(undefined)
  vi.mocked(compressPhoto).mockImplementation(async (file) => file)
  vi.mocked(fetchReviewPhotoCandidates).mockResolvedValue({
    records: [
      {
        reviewId: '9',
        position: 0,
        url: '/api/reviews/9/photos/0',
        username: '原作者',
        body: '同店点评',
        createdAt: '2026-11-22T00:00:00Z',
      },
    ],
    hasMore: false,
  })
  vi.mocked(readReviewPhoto).mockResolvedValue(new Blob(['review-jpeg'], { type: 'image/jpeg' }))
  host = document.createElement('div')
  document.body.append(host)
})
afterEach(() => {
  app?.unmount()
  host?.remove()
  restore()
  vi.unstubAllGlobals()
  clearAccessToken()
})
async function mount() {
  app = createApp(AdminRestaurantPhotos, { id: '901', onSelection: selection, onState: state })
  app.mount(host)
  await settle()
}
async function click(text: string) {
  ;[...host.querySelectorAll<HTMLButtonElement>('button')]
    .find((button) => button.textContent?.trim() === text)!
    .click()
  await settle()
}

it('已有照片保留稳定关联ID，封面和删除先改草稿，取消不调用云端删除', async () => {
  await mount()
  expect(state.mock.lastCall?.[0]).toMatchObject({ loading: false, dirty: false, error: '' })
  await click('设为封面')
  expect(selection.mock.lastCall?.[0]).toEqual([{ photoId: '2' }, { photoId: '1' }])
  host.querySelector<HTMLButtonElement>('[aria-label="移除第2张商户照片"]')!.click()
  await settle()
  expect(selection.mock.lastCall?.[0]).toEqual([{ photoId: '2' }])
  expect(state.mock.lastCall?.[0].dirty).toBe(true)
  expect(cancelImage).not.toHaveBeenCalled()
})
it('选用点评照片重新上传为RESTAURANT文件，保留原点评来源，换号清理未绑定草稿', async () => {
  await mount()
  await click('从点评中选择')
  host.querySelector<HTMLButtonElement>('.candidate-photo')!.click()
  await settle()
  await click('添加所选照片')
  expect(readReviewPhoto).toHaveBeenCalledOnce()
  expect(uploadImage).toHaveBeenCalledWith(expect.any(File), 'admin-photo-session', 'RESTAURANT')
  expect(selection.mock.lastCall?.[0]).toEqual([
    { photoId: '1' },
    { photoId: '2' },
    { fileId: '501' },
  ])
  expect(host.textContent).toContain('来自点评')
  clearAccessToken()
  await settle()
  expect(cancelImage).toHaveBeenCalledWith('501', 'admin-photo-session')
})
it('相册读取失败保留错误，重读成功后才允许整份保存', async () => {
  vi.mocked(fetchRestaurantAlbum).mockRejectedValueOnce(new Error('相册读取失败'))
  await mount()
  expect(state.mock.lastCall?.[0].error).toContain('相册读取失败')
  await click('重新读取')
  expect(state.mock.lastCall?.[0]).toMatchObject({ dirty: false, loading: false, error: '' })
  expect(host.querySelectorAll('.managed-photo')).toHaveLength(2)
})
