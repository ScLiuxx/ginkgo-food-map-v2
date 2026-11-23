import { createApp, nextTick, ref, type App } from 'vue'
import { createPinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setAccessToken, clearAccessToken } from '@/shared/api/authSession'
import { fetchRestaurants, fetchRestaurantCategories } from '@/modules/restaurant'
import { useAdminRestaurants } from './useAdminRestaurants'

vi.mock('@/modules/restaurant', () => ({
  fetchRestaurants: vi.fn(),
  fetchRestaurantCategories: vi.fn(),
}))

/** 验证授权快照、写入结果不明与旧查询覆盖这些真实交互边界。 */
describe('管理员商户维护', () => {
  let app: App, host: HTMLDivElement, model: ReturnType<typeof useAdminRestaurants>
  const settle = async () => {
    for (let i = 0; i < 10; i++) await nextTick()
  }
  beforeEach(async () => {
    vi.resetAllMocks()
    setAccessToken('admin-a')
    vi.mocked(fetchRestaurants).mockResolvedValue({
      records: [],
      page: 1,
      pageSize: 20,
      total: 0,
      totalPages: 0,
    })
    vi.mocked(fetchRestaurantCategories).mockResolvedValue([])
    host = document.createElement('div')
    app = createApp({
      setup() {
        model = useAdminRestaurants(ref(true))
        return () => null
      },
    }).use(createPinia())
    app.mount(host)
    await settle()
  })
  afterEach(() => {
    app.unmount()
    host.remove()
    clearAccessToken()
  })
  it('页码、每页数量和跳页均请求后端分页，未查询的草稿不影响翻页', async () => {
    vi.mocked(fetchRestaurants).mockResolvedValue({
      records: [],
      page: 1,
      pageSize: 20,
      total: 200,
      totalPages: 10,
    })
    await model.reload()
    model.keyword.value = '未提交的关键词'
    model.page.value = 3
    await settle()
    expect(fetchRestaurants).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: '' }),
      3,
      20,
      expect.any(AbortSignal),
    )
    model.pageSize.value = 50
    await settle()
    expect(model.page.value).toBe(1)
    expect(fetchRestaurants).toHaveBeenLastCalledWith(
      expect.anything(),
      1,
      50,
      expect.any(AbortSignal),
    )
    model.jumpPage.value = '4'
    model.jump()
    await settle()
    expect(model.page.value).toBe(4)
    model.jumpPage.value = '999'
    model.jump()
    expect(model.page.value).toBe(4)
    model.search()
    await settle()
    expect(fetchRestaurants).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: '未提交的关键词' }),
      1,
      50,
      expect.any(AbortSignal),
    )
  })
})
