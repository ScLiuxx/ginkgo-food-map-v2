import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { mockDialog } from '@/shared/test/mockDialog'
import { clearAccessToken, setAccessToken } from '@/shared/api/authSession'
import {
  fetchRestaurantManagement,
  deleteRestaurant,
  type RestaurantManagement,
} from '../api/restaurantApi'
import RestaurantDeleteDialog from './RestaurantDeleteDialog.vue'

vi.mock('../api/restaurantApi', () => ({
  fetchRestaurantManagement: vi.fn(),
  deleteRestaurant: vi.fn(),
}))
vi.mock('../model/catalog.store', () => ({ useRestaurantCatalog: () => ({ invalidate: vi.fn() }) }))
const snapshot = {
  restaurant: { id: '9007199254740993', name: '授权后的店名' },
  deleted: false,
} as RestaurantManagement
let app: App, host: HTMLDivElement, restore: () => void
const deleted = vi.fn(),
  close = vi.fn()
async function settle() {
  for (let i = 0; i < 12; i++) await nextTick()
}
beforeEach(() => {
  vi.resetAllMocks()
  setAccessToken('admin-a')
  restore = mockDialog()
  vi.mocked(fetchRestaurantManagement).mockResolvedValue(snapshot)
  vi.mocked(deleteRestaurant).mockResolvedValue({ ...snapshot, deleted: true })
  host = document.createElement('div')
  document.body.append(host)
})
afterEach(() => {
  app?.unmount()
  host.remove()
  restore()
  clearAccessToken()
})
async function mount() {
  app = createApp(RestaurantDeleteDialog, {
    id: snapshot.restaurant.id,
    name: 'RestaurantListFixture',
    onDeleted: deleted,
    onClose: close,
  })
  app.mount(host)
  await settle()
}
function button(name: string) {
  return [...host.querySelectorAll<HTMLButtonElement>('button')].find(
    (b) => b.textContent?.trim() === name,
  )!
}
it('打开即显示一次确认，按授权快照和完整字符串ID删除', async () => {
  await mount()
  expect(host.querySelector('dialog')?.open).toBe(true)
  expect(host.textContent).toContain('授权后的店名')
  expect(deleteRestaurant).not.toHaveBeenCalled()
  button('确认删除').click()
  await settle()
  expect(deleteRestaurant).toHaveBeenCalledExactlyOnceWith(snapshot.restaurant.id, 'admin-a')
  expect(deleted).toHaveBeenCalledOnce()
  expect(close).toHaveBeenCalledOnce()
})
it('取消和Esc不写入', async () => {
  await mount()
  host.querySelector('dialog')!.dispatchEvent(new Event('cancel', { cancelable: true }))
  await settle()
  expect(deleteRestaurant).not.toHaveBeenCalled()
  expect(close).toHaveBeenCalledOnce()
})
it('读取失败原位重读，已删除目标禁止继续删除', async () => {
  vi.mocked(fetchRestaurantManagement).mockRejectedValueOnce(new Error('读取失败'))
  await mount()
  expect(button('确认删除').disabled).toBe(true)
  vi.mocked(fetchRestaurantManagement).mockResolvedValue({ ...snapshot, deleted: true })
  button('重新读取').click()
  await settle()
  expect(host.textContent).toContain('已经删除')
  expect(host.querySelector('.confirm-delete')).toBeNull()
  expect(deleteRestaurant).not.toHaveBeenCalled()
})
it('删除超时不自动重试，重新核对后才允许再次确认', async () => {
  vi.mocked(deleteRestaurant).mockRejectedValueOnce(new Error('连接超时'))
  await mount()
  button('确认删除').click()
  await settle()
  expect(host.textContent).toContain('请重新读取')
  expect(button('确认删除').disabled).toBe(true)
  button('确认删除').click()
  await settle()
  expect(deleteRestaurant).toHaveBeenCalledTimes(1)
  button('重新读取').click()
  await settle()
  button('确认删除').click()
  await settle()
  expect(deleteRestaurant).toHaveBeenCalledTimes(2)
})
it('提交期间阻止重复写入和关闭', async () => {
  let resolve!: (value: RestaurantManagement) => void
  vi.mocked(deleteRestaurant).mockReturnValue(
    new Promise((done) => {
      resolve = done
    }),
  )
  await mount()
  button('确认删除').click()
  await settle()
  expect(button('取消').disabled).toBe(true)
  host.querySelector('dialog')!.dispatchEvent(new Event('cancel', { cancelable: true }))
  await settle()
  expect(close).not.toHaveBeenCalled()
  expect(deleteRestaurant).toHaveBeenCalledTimes(1)
  resolve({ ...snapshot, deleted: true })
  await settle()
  expect(deleted).toHaveBeenCalledOnce()
})
it('账号变化使在途读取失效，旧会话不得继续删除', async () => {
  let resolve!: (value: RestaurantManagement) => void
  vi.mocked(fetchRestaurantManagement).mockReturnValue(
    new Promise((done) => {
      resolve = done
    }),
  )
  await mount()
  setAccessToken('admin-b')
  await settle()
  resolve(snapshot)
  await settle()
  expect(host.textContent).toContain('登录状态已变化')
  expect(button('确认删除').disabled).toBe(true)
  expect(deleteRestaurant).not.toHaveBeenCalled()
})
