import { createApp, nextTick, type App } from 'vue'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setAccessToken, clearAccessToken } from '@/shared/api/authSession'
import { mockDialog } from '@/shared/test/mockDialog'
import {
  fetchManagedWindows,
  saveWindow,
  setWindowDeleted,
  fetchWindowPhotos,
  type CanteenWindow,
} from '@/modules/restaurant/api/canteenWindowApi'
import { fetchRestaurantManagement } from '@/modules/restaurant/api/restaurantApi'
import WindowManagement from './WindowManagement.vue'
import WindowEditor from './WindowEditor.vue'

vi.mock('@/modules/restaurant/api/canteenWindowApi', () => ({
  fetchManagedWindows: vi.fn(),
  saveWindow: vi.fn(),
  setWindowDeleted: vi.fn(),
  fetchWindowPhotos: vi.fn(),
  addWindowPhoto: vi.fn(),
  removeWindowPhoto: vi.fn(),
}))
vi.mock('@/modules/restaurant/api/restaurantApi', () => ({ fetchRestaurantManagement: vi.fn() }))
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
const base = '/admin/restaurants/901/windows'
const settle = async () => {
  for (let i = 0; i < 25; i++) await nextTick()
}

/** 真实路由与页面验证创建防重、返回、换号隔离及删除恢复；网络全部模拟。 */
describe('窗口独立管理页', () => {
  let app: App,
    host: HTMLDivElement,
    router: ReturnType<typeof createRouter>,
    restoreDialog: () => void
  beforeEach(async () => {
    vi.resetAllMocks()
    restoreDialog = mockDialog()
    setAccessToken('original-admin')
    vi.mocked(fetchManagedWindows).mockResolvedValue([row])
    vi.mocked(saveWindow).mockResolvedValue(row)
    vi.mocked(fetchWindowPhotos).mockResolvedValue([])
    vi.mocked(setWindowDeleted).mockResolvedValue(undefined)
    vi.spyOn(globalThis, 'scrollTo').mockImplementation(() => {})
    host = document.createElement('div')
    document.body.append(host)
    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/admin/restaurants/:id/windows', component: WindowManagement, props: true },
        {
          path: '/admin/restaurants/:id/windows/new',
          component: WindowEditor,
          props: (route) => ({ id: route.params.id }),
        },
        {
          path: '/admin/restaurants/:id/windows/:windowId/edit',
          component: WindowEditor,
          props: true,
        },
        { path: '/canteen-windows/:id', component: { template: '<p>窗口详情</p>' } },
        { path: '/admin/restaurants', component: { template: '<p>商户管理</p>' } },
      ],
    })
    await router.push(base)
    await router.isReady()
    app = createApp({
      components: { RouterView },
      template:
        '<RouterView v-slot="{ Component, route }"><component :is="Component" :key="route.path" /></RouterView>',
    })
      .use(createPinia())
      .use(router)
    app.mount(host)
    await settle()
  })
  afterEach(() => {
    app.unmount()
    host.remove()
    clearAccessToken()
    restoreDialog()
    vi.restoreAllMocks()
  })
  const button = (text: string) =>
    [...host.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === text,
    )!
  const link = (text: string) =>
    [...host.querySelectorAll<HTMLElement>('a')].find((a) => a.textContent?.trim() === text)!
  async function navigate(path: string) {
    await router.push(path)
    await settle()
  }
  async function fillName(value: string) {
    const name = host.querySelector<HTMLInputElement>('form input')!
    name.value = value
    name.dispatchEvent(new Event('input', { bubbles: true }))
    await settle()
  }
  async function save() {
    host
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await settle()
  }

  it('列表仅保留三个操作，编辑进入独立页并合并照片，返回恢复按钮焦点', async () => {
    expect(host.querySelector('.window-actions')?.textContent?.replace(/\s/g, '')).toBe(
      '编辑查看删除',
    )
    expect(host.textContent).not.toContain('ID：')
    link('编辑').click()
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe(base + '/1/edit'))
    await settle()
    expect(host.querySelector('table')).toBeNull()
    expect(host.querySelector('h1')?.textContent).toBe('编辑窗口')
    expect(fetchWindowPhotos).toHaveBeenCalledWith('1', expect.any(AbortSignal))
    link('‹ 返回窗口管理').click()
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe(base))
    await settle()
    expect(document.activeElement?.textContent).toBe('编辑')
  })
  it('未知创建结果冻结内容，重试原UUID后进入已创建窗口的编辑页', async () => {
    vi.mocked(saveWindow).mockRejectedValueOnce(new Error('网络超时')).mockResolvedValueOnce(row)
    await navigate(base + '/new')
    await fillName('新窗口')
    await save()
    const original = vi.mocked(saveWindow).mock.calls[0]!
    expect(original[2].clientRequestId).toMatch(/^[0-9a-f-]{36}$/)
    expect(host.querySelector('fieldset')!.disabled).toBe(true)
    expect(host.textContent).toContain('上次创建结果尚未确认')
    await router.push(base)
    expect(router.currentRoute.value.path).toBe(base + '/new')
    await save()
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe(base + '/1/edit'))
    await settle()
    expect(vi.mocked(saveWindow).mock.calls[1]).toEqual(original)
    expect(fetchWindowPhotos).toHaveBeenCalledTimes(1)
  })
  it('查看留在管理端只读弹窗，照片失败可重试，Esc关闭', async () => {
    vi.mocked(fetchWindowPhotos).mockRejectedValueOnce(new Error('照片读取失败'))
    button('查看').click()
    await settle()
    const dialog = host.querySelector<HTMLDialogElement>('.window-preview-dialog')!
    expect(dialog.open).toBe(true)
    expect(router.currentRoute.value.path).toBe(base)
    expect(dialog.textContent).toContain('真实窗口')
    expect(dialog.textContent).toContain('照片读取失败')
    button('重试读取照片').click()
    await settle()
    expect(fetchWindowPhotos).toHaveBeenCalledTimes(2)
    dialog.dispatchEvent(new Event('cancel', { cancelable: true }))
    await settle()
    expect(host.querySelector('.window-preview-dialog')).toBeNull()
    expect(saveWindow).not.toHaveBeenCalled()
  })
  it('查看过程中目标已删除时展示删除状态，不读取公开照片', async () => {
    vi.mocked(fetchManagedWindows).mockResolvedValue([{ ...row, deleted: true }])
    button('查看').click()
    await settle()
    expect(host.querySelector('.window-preview-dialog')?.textContent).toContain('已删除')
    expect(fetchWindowPhotos).not.toHaveBeenCalled()
  })
  it('编辑保存成功但列表读取失败时只重试读取，不重复写入', async () => {
    await navigate(base + '/1/edit')
    await fillName('更新后的窗口')
    vi.mocked(fetchManagedWindows).mockRejectedValueOnce(new Error('读取失败'))
    await save()
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe(base))
    await settle()
    expect(host.textContent).toContain('窗口已保存')
    expect(host.textContent).toContain('读取失败')
    button('重试读取列表').click()
    await settle()
    expect(saveWindow).toHaveBeenCalledTimes(1)
    expect(host.textContent).toContain('真实窗口')
  })
  it('删除和恢复需确认，取消不写入，失败保留重试入口', async () => {
    button('删除').click()
    await settle()
    button('取消').click()
    await settle()
    expect(setWindowDeleted).not.toHaveBeenCalled()
    button('删除').click()
    await settle()
    vi.mocked(setWindowDeleted).mockRejectedValueOnce(new Error('删除失败'))
    button('删除窗口').click()
    await settle()
    expect(host.textContent).toContain('删除失败')
    vi.mocked(fetchManagedWindows).mockResolvedValue([{ ...row, deleted: true }])
    button('删除窗口').click()
    await settle()
    expect(setWindowDeleted).toHaveBeenLastCalledWith('1', true, 'original-admin')
    expect(host.querySelector('.window-actions')?.textContent?.trim()).toBe('恢复')
    button('恢复').click()
    await settle()
    vi.mocked(fetchManagedWindows).mockResolvedValue([row])
    button('恢复窗口').click()
    await settle()
    expect(setWindowDeleted).toHaveBeenLastCalledWith('1', false, 'original-admin')
    expect(link('编辑')).toBeDefined()
  })
  it('换号清空资料并忽略旧账号迟到的保存回执', async () => {
    let resolve!: (item: CanteenWindow) => void
    vi.mocked(saveWindow).mockReturnValueOnce(
      new Promise((done) => {
        resolve = done
      }),
    )
    await navigate(base + '/new')
    await fillName('新窗口')
    await save()
    setAccessToken('new-account')
    await settle()
    resolve(row)
    await settle()
    expect(host.textContent).toContain('登录身份已变化')
    expect(host.textContent).not.toContain('真实窗口')
    expect(router.currentRoute.value.path).toBe(base + '/new')
  })
  it('未保存返回显示站内确认，继续编辑保留草稿，离开才丢弃', async () => {
    await navigate(base + '/1/edit')
    await fillName('尚未保存')
    link('取消').click()
    await settle()
    const dialog = document.querySelector<HTMLDialogElement>('.leave-confirmation')!
    await vi.waitFor(() => expect(dialog.open).toBe(true))
    dialog.querySelector<HTMLButtonElement>('button')!.click()
    await settle()
    expect(host.querySelector<HTMLInputElement>('form input')!.value).toBe('尚未保存')
    link('取消').click()
    await vi.waitFor(() => expect(dialog.open).toBe(true))
    dialog.querySelector<HTMLButtonElement>('.leave')!.click()
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe(base))
    expect(saveWindow).not.toHaveBeenCalled()
  })
  it('新建无窗口的食堂仍读取真实名称，创建前不请求照片', async () => {
    vi.mocked(fetchManagedWindows).mockResolvedValue([])
    vi.mocked(fetchRestaurantManagement).mockResolvedValue({
      restaurant: { name: '空食堂', restaurantType: 'CANTEEN' },
      deleted: false,
    } as Awaited<ReturnType<typeof fetchRestaurantManagement>>)
    await navigate(base + '/new')
    expect(host.textContent).toContain('空食堂')
    expect(fetchWindowPhotos).not.toHaveBeenCalled()
  })
  it.each([
    { ...row, deleted: true },
    { ...row, canteenId: '902' },
  ])('删除或跨食堂窗口不显示编辑和照片', async (target) => {
    vi.mocked(fetchManagedWindows).mockResolvedValue([target])
    await navigate(base + '/1/edit')
    expect(host.textContent).toContain('已删除或不属于当前食堂')
    expect(host.querySelector('form')).toBeNull()
    expect(fetchWindowPhotos).not.toHaveBeenCalled()
  })
  it('未保存期间浏览器关闭提醒，保存期间阻止路由切换', async () => {
    await navigate(base + '/1/edit')
    await fillName('新名称')
    const event = new Event('beforeunload', { cancelable: true })
    globalThis.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)
    let resolve!: (item: CanteenWindow) => void
    vi.mocked(saveWindow).mockReturnValueOnce(
      new Promise((done) => {
        resolve = done
      }),
    )
    await save()
    await router.push(base)
    expect(router.currentRoute.value.path).toBe(base + '/1/edit')
    expect(host.querySelector<HTMLInputElement>('input[type=file]')!.disabled).toBe(true)
    resolve(row)
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe(base))
  })
})
