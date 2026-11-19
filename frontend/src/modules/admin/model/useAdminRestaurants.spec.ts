import { createApp, nextTick, ref, type App } from 'vue'
import { createPinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setAccessToken, clearAccessToken } from '@/shared/api/authSession'
import {
  fetchRestaurants,
  fetchRestaurantCategories,
  fetchRestaurantManagement,
  deleteRestaurant,
} from '@/modules/restaurant'
import { useAdminRestaurants } from './useAdminRestaurants'

vi.mock('@/modules/restaurant', () => ({
  fetchRestaurants: vi.fn(),
  fetchRestaurantCategories: vi.fn(),
  fetchRestaurantManagement: vi.fn(),
  deleteRestaurant: vi.fn(),
  useRestaurantCatalog: () => ({ invalidate: vi.fn() }),
}))

/** 验证授权快照、写入结果不明与旧查询覆盖这些真实交互边界。 */
describe('管理员商户维护', () => {
  let app: App, host: HTMLDivElement, model: ReturnType<typeof useAdminRestaurants>
  const snapshot = {
    restaurant: { id: '9007199254740993', name: '测试店' },
    deleted: false,
  } as Awaited<ReturnType<typeof fetchRestaurantManagement>>
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
    vi.mocked(fetchRestaurantManagement).mockResolvedValue(snapshot)
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
  it('删除必须明确确认，失败后必须重新读取状态且不自动重试', async () => {
    await model.inspect(snapshot.restaurant.id)
    await model.applyAction()
    expect(deleteRestaurant).not.toHaveBeenCalled()
    model.confirming.value = true
    vi.mocked(deleteRestaurant).mockRejectedValue(new Error('连接超时'))
    await model.applyAction()
    expect(deleteRestaurant).toHaveBeenCalledExactlyOnceWith(snapshot.restaurant.id, 'admin-a')
    expect(model.selected.value).toBeNull()
    expect(model.confirming.value).toBe(false)
    expect(model.actionError.value).toContain('刷新列表并重新选择店铺')
    expect(model.notice.value).toBe('')
    model.confirming.value = true
    await model.applyAction()
    expect(deleteRestaurant).toHaveBeenCalledTimes(1)
    await model.inspect(snapshot.restaurant.id)
    vi.mocked(deleteRestaurant).mockResolvedValue({ ...snapshot, deleted: true })
    model.confirming.value = true
    await model.applyAction()
    expect(deleteRestaurant).toHaveBeenCalledTimes(2)
  })
  it('删除使用完整字符串ID，成功后关闭详情并刷新目录', async () => {
    vi.mocked(deleteRestaurant).mockResolvedValue({
      ...snapshot,
      deleted: true,
    })
    await model.inspect(snapshot.restaurant.id)
    model.confirming.value = true
    await model.applyAction()
    expect(deleteRestaurant).toHaveBeenCalledExactlyOnceWith(snapshot.restaurant.id, 'admin-a')
    expect(model.selected.value).toBeNull()
    expect(model.notice.value).toBe('「测试店」已删除。')
    model.confirming.value = true
    await model.applyAction()
    expect(deleteRestaurant).toHaveBeenCalledTimes(1)
    expect(fetchRestaurants).toHaveBeenCalledTimes(2)
  })
  it('列表过期时读取到已删除店铺，不能继续操作', async () => {
    vi.mocked(fetchRestaurantManagement).mockResolvedValue({ ...snapshot, deleted: true })
    await model.inspect(snapshot.restaurant.id)
    model.confirming.value = true
    await model.applyAction()
    expect(deleteRestaurant).not.toHaveBeenCalled()
    expect(model.selected.value?.deleted).toBe(true)
  })
  it('账号变化后丢弃待处理快照，禁止旧身份写入', async () => {
    await model.inspect(snapshot.restaurant.id)
    model.confirming.value = true
    setAccessToken('admin-b')
    await settle()
    await model.applyAction()
    expect(model.selected.value).toBeNull()
    expect(model.sessionChanged.value).toBe(true)
    expect(deleteRestaurant).not.toHaveBeenCalled()
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
  it('较慢的旧查询不能覆盖新的店铺快照', async () => {
    let resolve!: (value: typeof snapshot) => void
    vi.mocked(fetchRestaurantManagement).mockReturnValueOnce(
      new Promise((done) => {
        resolve = done
      }),
    )
    const older = model.inspect('1')
    await model.inspect(snapshot.restaurant.id)
    resolve({ ...snapshot, restaurant: { ...snapshot.restaurant, id: '1' } })
    await older
    expect(model.selected.value?.restaurant.id).toBe(snapshot.restaurant.id)
  })
})
