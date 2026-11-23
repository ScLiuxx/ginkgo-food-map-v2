import { createApp, h, nextTick, ref, type App } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearAccessToken, setAccessToken } from '@/shared/api/authSession'
import { fetchRestaurantManagement, type RestaurantManagement } from '../api/restaurantApi'
import { responseFixture } from '../api/restaurant.test-data'
import RestaurantManagementPreview from './RestaurantManagementPreview.vue'

vi.mock('../api/restaurantApi', async (original) => ({
  ...(await original<typeof import('../api/restaurantApi')>()),
  fetchRestaurantManagement: vi.fn(),
}))
vi.mock('./RestaurantAlbum.vue', () => ({ default: { template: '<div class="test-album" />' } }))

describe('administrator read-only restaurant preview', () => {
  let app: App
  let host: HTMLDivElement
  const id = ref('88')
  const result = (name = '当前店铺', deleted = false): RestaurantManagement => ({
    restaurant: { ...responseFixture, id: id.value, name },
    deleted,
  })
  async function settle() {
    for (let index = 0; index < 6; index++) {
      await nextTick()
    }
  }
  async function mount() {
    app = createApp({ render: () => h(RestaurantManagementPreview, { id: id.value }) })
    app.mount(host)
    await settle()
  }
  beforeEach(() => {
    vi.resetAllMocks()
    id.value = '88'
    setAccessToken('preview-admin')
    host = document.createElement('div')
    document.body.append(host)
    vi.mocked(fetchRestaurantManagement).mockResolvedValue(result())
  })
  afterEach(() => {
    app?.unmount()
    host.remove()
    clearAccessToken()
  })
  it('uses the authorized management read and provides no editing or posting actions', async () => {
    await mount()
    expect(fetchRestaurantManagement).toHaveBeenCalledWith(
      '88',
      'preview-admin',
      expect.any(AbortSignal),
    )
    expect(host.querySelector('h1')?.textContent).toBe('当前店铺')
    expect(host.querySelector('.test-album')).not.toBeNull()
    expect(host.querySelector('form, input, textarea, button')).toBeNull()
    expect(host.textContent).toContain('人均待补')
  })
  it('retries a failed read and hides the public album for a deleted restaurant', async () => {
    vi.mocked(fetchRestaurantManagement).mockRejectedValueOnce(new Error('读取失败'))
    await mount()
    expect(host.querySelector('[role=alert]')?.textContent).toContain('读取失败')
    vi.mocked(fetchRestaurantManagement).mockResolvedValue(result('已下架店铺', true))
    host.querySelector('button')?.click()
    await settle()
    expect(host.textContent).toContain('已删除')
    expect(host.querySelector('.test-album')).toBeNull()
    expect(fetchRestaurantManagement).toHaveBeenCalledTimes(2)
  })
  it('aborts the former read and ignores late data after changing restaurants', async () => {
    let resolveOld!: (value: RestaurantManagement) => void
    vi.mocked(fetchRestaurantManagement).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve
        }),
    )
    await mount()
    const signal = vi.mocked(fetchRestaurantManagement).mock.calls[0]![2]!
    id.value = '99'
    vi.mocked(fetchRestaurantManagement).mockResolvedValue(result('新店铺'))
    await settle()
    expect(signal.aborted).toBe(true)
    resolveOld(result('旧店铺'))
    await settle()
    expect(host.querySelector('h1')?.textContent).toBe('新店铺')
  })
  it('clears the old session immediately and refuses its late response after logout', async () => {
    let resolveOld!: (value: RestaurantManagement) => void
    vi.mocked(fetchRestaurantManagement).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve
        }),
    )
    await mount()
    clearAccessToken()
    await settle()
    resolveOld(result('旧会话资料'))
    await settle()
    expect(host.querySelector('h1')).toBeNull()
    expect(host.querySelector('[role=alert]')).toBeNull()
    expect(fetchRestaurantManagement).toHaveBeenCalledTimes(1)
  })
})
