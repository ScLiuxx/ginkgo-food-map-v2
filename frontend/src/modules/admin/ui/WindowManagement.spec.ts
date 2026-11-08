import { createApp, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setAccessToken, clearAccessToken } from '@/shared/api/authSession'
import {
  fetchManagedWindows,
  saveWindow,
  setWindowDeleted,
  type CanteenWindow,
} from '@/modules/restaurant/api/canteenWindowApi'
import WindowManagement from './WindowManagement.vue'

vi.mock('@/modules/restaurant/api/canteenWindowApi', () => ({
  fetchManagedWindows: vi.fn(),
  saveWindow: vi.fn(),
  setWindowDeleted: vi.fn(),
}))
vi.mock('@/modules/restaurant/model/catalog.store', () => ({
  useRestaurantCatalog: () => ({ invalidate: vi.fn() }),
}))

const row = {
  id: '1',
  canteenId: '901',
  canteenName: '测试食堂',
  name: '真实窗口',
  floorNumber: 1,

  description: null,
  priceKind: 'UNKNOWN',
  pricePerPerson: null,
  businessStatus: 'OPERATING',
  deleted: false,
} as CanteenWindow
const settle = async () => {
  for (let i = 0; i < 15; i++) await nextTick()
}

/** 使用真实管理组件，验证未知写入结果的重试、写成功读失败、确认删除及换号隔离。 */
describe('窗口管理', () => {
  let app: App, host: HTMLDivElement
  beforeEach(async () => {
    vi.clearAllMocks()
    setAccessToken('original-admin')
    vi.mocked(fetchManagedWindows).mockResolvedValue([row])
    vi.mocked(saveWindow).mockResolvedValue(row)
    vi.mocked(setWindowDeleted).mockResolvedValue(undefined)
    host = document.createElement('div')
    document.body.append(host)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
    })
    await router.push('/admin/restaurants/901/windows')
    await router.isReady()
    app = createApp({ render: () => h(WindowManagement, { id: '901' }) }).use(router)
    app.mount(host)
    await settle()
  })
  afterEach(() => {
    app.unmount()
    host.remove()
    clearAccessToken()
  })
  const button = (text: string) =>
    [...host.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === text,
    )!
  async function openNew() {
    button('＋ 新增窗口').click()
    await settle()
    const name = host.querySelector<HTMLInputElement>('form input')!
    name.value = '新窗口'
    name.dispatchEvent(new Event('input', { bubbles: true }))
    await settle()
  }
  async function save() {
    host
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await settle()
  }
  it('未知创建结果冻结内容，并使用相同UUID和凭证确认原请求', async () => {
    vi.mocked(saveWindow).mockRejectedValueOnce(new Error('网络超时')).mockResolvedValueOnce(row)
    await openNew()
    await save()
    const original = vi.mocked(saveWindow).mock.calls[0]!
    expect(original[2].clientRequestId).toMatch(/^[0-9a-f-]{36}$/)
    expect(host.querySelector('fieldset')!.disabled).toBe(true)
    expect(button('＋ 新增窗口').disabled).toBe(true)
    expect(host.textContent).toContain('上次创建结果尚未确认')
    await save()
    expect(vi.mocked(saveWindow).mock.calls[1]).toEqual(original)
    expect(host.querySelector('form')).toBeNull()
  })
  it('保存成功但读取失败时只重试读取', async () => {
    await openNew()
    vi.mocked(fetchManagedWindows).mockRejectedValueOnce(new Error('读取失败'))
    await save()
    expect(host.textContent).toContain('窗口已保存')
    expect(host.textContent).toContain('读取失败')
    expect(host.querySelector('form')).toBeNull()
    button('刷新列表').click()
    await settle()
    expect(saveWindow).toHaveBeenCalledTimes(1)
    expect(fetchManagedWindows).toHaveBeenCalledTimes(3)
  })
  it('删除必须确认，取消不写入，删除成功保留真实恢复入口', async () => {
    button('删除').click()
    await settle()
    expect(setWindowDeleted).not.toHaveBeenCalled()
    button('取消').click()
    await settle()
    expect(setWindowDeleted).not.toHaveBeenCalled()
    button('删除').click()
    await settle()
    vi.mocked(fetchManagedWindows).mockResolvedValue([{ ...row, deleted: true }])
    button('确认操作').click()
    await settle()
    expect(setWindowDeleted).toHaveBeenCalledWith('1', true, 'original-admin')
    expect(button('恢复')).toBeDefined()
  })
  it('换号后清空资料并忽略旧账号迟到的保存回执', async () => {
    let resolve!: (item: CanteenWindow) => void
    vi.mocked(saveWindow).mockReturnValueOnce(
      new Promise((r) => {
        resolve = r
      }),
    )
    await openNew()
    await save()
    setAccessToken('new-account')
    await settle()
    resolve(row)
    await settle()
    expect(host.textContent).toContain('登录身份已变化')
    expect(host.textContent).not.toContain('真实窗口')
    expect(host.textContent).not.toContain('窗口已保存')
    expect(fetchManagedWindows).toHaveBeenCalledTimes(1)
  })
})
