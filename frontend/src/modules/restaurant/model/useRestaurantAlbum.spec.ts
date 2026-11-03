import { effectScope, nextTick, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  fetchRestaurantAlbum,
  fetchRestaurantAlbumImage,
  type RestaurantAlbumPage,
} from '../api/restaurantAlbumApi'
import { useRestaurantAlbum } from './useRestaurantAlbum'

vi.mock('../api/restaurantAlbumApi', () => ({
  fetchRestaurantAlbum: vi.fn(),
  fetchRestaurantAlbumImage: vi.fn(),
}))
const page = (ids = ['11', '12'], nextCursor: string | null = null): RestaurantAlbumPage => ({
  records: ids.map((id, index) => ({
    id,
    position: index,
    cover: index === 0,
    url: '/unused/' + id,
  })),
  nextCursor,
})
const jpeg = () => new Blob(['jpeg'], { type: 'image/jpeg' })
const settle = async () => {
  for (let index = 0; index < 16; index++) await nextTick()
}

describe('正式商户相册状态', () => {
  let scope: ReturnType<typeof effectScope>, state: ReturnType<typeof useRestaurantAlbum>
  const id = ref('1')
  beforeEach(() => {
    vi.resetAllMocks()
    id.value = '1'
    scope = effectScope()
    let serial = 0
    vi.stubGlobal(
      'URL',
      class extends URL {
        static createObjectURL = vi.fn(() => 'blob:album-' + ++serial)
        static revokeObjectURL = vi.fn()
      },
    )
    vi.mocked(fetchRestaurantAlbum).mockResolvedValue(page())
    vi.mocked(fetchRestaurantAlbumImage).mockResolvedValue(jpeg())
  })
  afterEach(() => {
    scope.stop()
    vi.unstubAllGlobals()
  })
  const start = async () => {
    state = scope.run(() => useRestaurantAlbum(() => id.value))!
    await settle()
  }

  it('独立读取正式关联、默认选择封面，每张JPEG保留短期Blob', async () => {
    await start()
    expect(fetchRestaurantAlbum).toHaveBeenCalledWith('1', null, expect.any(AbortSignal))
    expect(state.selected.value?.id).toBe('11')
    expect(state.photos.value.map((photo) => photo.src)).toEqual(['blob:album-1', 'blob:album-2'])
    state.selectPhoto('12')
    expect(state.selected.value?.id).toBe('12')
    expect(fetchRestaurantAlbumImage).toHaveBeenCalledTimes(2)
  })

  it('元数据失败与真实空相册分开，重试不补造点评图', async () => {
    vi.mocked(fetchRestaurantAlbum).mockRejectedValueOnce(new Error('服务暂不可用'))
    await start()
    expect(state.error.value).toBe('服务暂不可用')
    expect(state.photos.value).toEqual([])
    vi.mocked(fetchRestaurantAlbum).mockResolvedValueOnce(page([]))
    state.reload()
    await settle()
    expect(state.error.value).toBe('')
    expect(state.photos.value).toEqual([])
    expect(fetchRestaurantAlbumImage).not.toHaveBeenCalled()
  })

  it('加载更多失败保留已加载图片与选择，重试使用原游标并追加', async () => {
    vi.mocked(fetchRestaurantAlbum).mockResolvedValueOnce(page(['11'], '11'))
    await start()
    vi.mocked(fetchRestaurantAlbum).mockRejectedValueOnce(new Error('分页失败'))
    await state.loadMore()
    expect(state.moreError.value).toBe('分页失败')
    expect(state.selected.value?.src).toBe('blob:album-1')
    expect(state.nextCursor.value).toBe('11')
    vi.mocked(fetchRestaurantAlbum).mockResolvedValueOnce({
      records: [{ id: '12', position: 1, cover: false, url: '/unused' }],
      nextCursor: null,
    })
    await state.loadMore()
    await settle()
    expect(fetchRestaurantAlbum).toHaveBeenLastCalledWith('1', '11', expect.any(AbortSignal))
    expect(state.photos.value.map((photo) => photo.id)).toEqual(['11', '12'])
    expect(state.selected.value?.id).toBe('11')
    expect(state.moreError.value).toBe('')
  })

  it('JPEG最多四个并发，卸载后排队和迟到图片不再创建URL', async () => {
    vi.mocked(fetchRestaurantAlbum).mockResolvedValueOnce(
      page(Array.from({ length: 9 }, (_, index) => String(index + 11))),
    )
    const finish: ((blob: Blob) => void)[] = []
    vi.mocked(fetchRestaurantAlbumImage).mockImplementation(
      () =>
        new Promise((resolve) => {
          finish.push(resolve)
        }),
    )
    await start()
    expect(fetchRestaurantAlbumImage).toHaveBeenCalledTimes(4)
    state.selectPhoto('19')
    finish[0]!(jpeg())
    await settle()
    expect(fetchRestaurantAlbumImage).toHaveBeenCalledTimes(5)
    expect(fetchRestaurantAlbumImage).toHaveBeenLastCalledWith('1', '19', expect.any(AbortSignal))
    scope.stop()
    finish.slice(1).forEach((resolve) => resolve(jpeg()))
    await settle()
    expect(fetchRestaurantAlbumImage).toHaveBeenCalledTimes(5)
    expect(URL.createObjectURL).toHaveBeenCalledTimes(1)
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:album-1')
    expect(vi.mocked(fetchRestaurantAlbumImage).mock.calls[0]![2].aborted).toBe(true)
  })

  it('切换商户后旧列表不能覆盖新相册', async () => {
    let finish!: (value: RestaurantAlbumPage) => void
    vi.mocked(fetchRestaurantAlbum).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    await start()
    id.value = '2'
    await settle()
    finish(page(['99']))
    await settle()
    expect(state.photos.value.map((photo) => photo.id)).toEqual(['11', '12'])
    expect(vi.mocked(fetchRestaurantAlbumImage).mock.calls.every((call) => call[0] === '2')).toBe(
      true,
    )
  })

  it('切换后旧图片响应不创建URL，也不会覆盖新照片', async () => {
    let finish!: (value: Blob) => void
    vi.mocked(fetchRestaurantAlbum).mockResolvedValue(page(['11']))
    vi.mocked(fetchRestaurantAlbumImage).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    await start()
    const oldSignal = vi.mocked(fetchRestaurantAlbumImage).mock.calls[0]![2]
    id.value = '2'
    await settle()
    finish(jpeg())
    await settle()
    expect(oldSignal.aborted).toBe(true)
    expect(URL.createObjectURL).toHaveBeenCalledTimes(1)
    expect(state.selected.value?.src).toBe('blob:album-1')
  })

  it('单图失败不移除关联或静默更换封面，可独立重试，解码失败释放URL', async () => {
    vi.mocked(fetchRestaurantAlbumImage).mockRejectedValueOnce(new Error('OSS暂不可用'))
    await start()
    expect(state.selected.value?.id).toBe('11')
    expect(state.selected.value?.error).toContain('OSS暂不可用')
    expect(state.photos.value).toHaveLength(2)
    state.loadPhoto('11')
    await settle()
    expect(state.selected.value?.src).toBeTruthy()
    const src = state.selected.value!.src
    state.failPhoto('11')
    expect(URL.revokeObjectURL).toHaveBeenCalledWith(src)
    expect(state.selected.value?.error).toContain('无法显示')
    state.loadPhoto('11')
    await settle()
    expect(state.selected.value?.error).toBe('')
  })

  it('分页游标不推进时停止追加并保留原相册', async () => {
    vi.mocked(fetchRestaurantAlbum).mockResolvedValue(page(['11'], '11'))
    await start()
    await state.loadMore()
    expect(state.moreError.value).toContain('未推进')
    expect(state.photos.value).toHaveLength(1)
  })
})
