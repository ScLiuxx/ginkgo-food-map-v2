import { captureLinkTarget } from '@/shared/test/captureLinkTarget'
import { AxiosError, AxiosHeaders } from 'axios'
import { createPinia, disposePinia } from 'pinia'
import { createApp, nextTick } from 'vue'
import type { App } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import type { Router } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearAccessToken, setAccessToken } from '@/shared/api/authSession'
import { deleteRestaurant, fetchRestaurantManagement } from '../api/restaurantApi'
import type { RestaurantManagement } from '../api/restaurantApi'
import { responseFixture } from '../api/restaurant.test-data'
import RestaurantManagementActions from './RestaurantManagementActions.vue'

vi.mock('../api/restaurantApi', () => ({
  deleteRestaurant: vi.fn(),
  fetchRestaurantManagement: vi.fn(),
}))
const snapshot: RestaurantManagement = { restaurant: responseFixture, deleted: false }
const forbidden = () =>
  new AxiosError('forbidden', '', undefined, undefined, {
    status: 403,
    statusText: '',
    data: {},
    headers: new AxiosHeaders(),
    config: { headers: new AxiosHeaders() },
  })

/** 维护控件以服务端管理快照为准，删除需二次确认，权限/身份变化不得产生假成功。 */
describe('restaurant management actions', () => {
  let app: App
  let host: HTMLDivElement
  let router: Router
  let pinia: ReturnType<typeof createPinia>
  const showModal = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal')
  const close = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'close')
  const settle = async () => {
    for (let i = 0; i < 8; i++) await nextTick()
  }
  beforeEach(() => {
    vi.resetAllMocks()
    setAccessToken('owner-session')
    vi.mocked(fetchRestaurantManagement).mockResolvedValue(snapshot)
    vi.mocked(deleteRestaurant).mockResolvedValue({ ...snapshot, deleted: true })
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
      configurable: true,
      value(this: HTMLDialogElement) {
        this.open = true
      },
    })
    Object.defineProperty(HTMLDialogElement.prototype, 'close', {
      configurable: true,
      value(this: HTMLDialogElement) {
        this.open = false
      },
    })
    host = document.createElement('div')
    document.body.append(host)
    pinia = createPinia()
    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/restaurants/:id', component: RestaurantManagementActions, props: true },
        ...['/', '/restaurants/:id/edit'].map((path) => ({
          path,
          component: { template: '<div />' },
        })),
      ],
    })
  })
  afterEach(() => {
    app?.unmount()
    disposePinia(pinia)
    host.remove()
    clearAccessToken()
    for (const [key, descriptor] of [
      ['showModal', showModal],
      ['close', close],
    ] as const) {
      if (descriptor) Object.defineProperty(HTMLDialogElement.prototype, key, descriptor)
      else Reflect.deleteProperty(HTMLDialogElement.prototype, key)
    }
  })
  async function mount() {
    await router.push(`/restaurants/${responseFixture.id}`)
    app = createApp(RouterView).use(pinia).use(router)
    app.mount(host)
    await settle()
  }
  async function confirm() {
    host.querySelector<HTMLButtonElement>('.management-buttons button')!.click()
    await settle()
  }
  it('does not query management anonymously', async () => {
    clearAccessToken()
    await mount()
    expect(fetchRestaurantManagement).not.toHaveBeenCalled()
    expect(host.querySelector('.management-buttons')).toBeNull()
  })
  it('silently hides controls when the server denies management', async () => {
    vi.mocked(fetchRestaurantManagement).mockRejectedValue(forbidden())
    await mount()
    expect(host.querySelector('.management-buttons')).toBeNull()
    expect(host.querySelector('[role="alert"]')).toBeNull()
  })
  it('shows authorized edit/delete controls and cancellation never writes', async () => {
    await mount()
    await confirm()
    expect(await captureLinkTarget(app, host.querySelector('a'))).toBe(
      `/restaurants/${responseFixture.id}/edit`,
    )
    expect(host.querySelector('dialog')?.textContent).toContain('0 条评价')
    expect(document.activeElement?.className).toBe('cancel-delete')
    host.querySelector<HTMLButtonElement>('.cancel-delete')!.click()
    await settle()
    expect(deleteRestaurant).not.toHaveBeenCalled()
    expect(host.querySelector('dialog')).toBeNull()
  })
  it('deletes exactly once and navigates only after confirmation succeeds', async () => {
    let resolve!: (value: RestaurantManagement) => void
    vi.mocked(deleteRestaurant).mockReturnValue(
      new Promise((done) => {
        resolve = done
      }),
    )
    await mount()
    await confirm()
    const button = host.querySelector<HTMLButtonElement>('.confirm-delete')!
    button.click()
    button.click()
    await settle()
    expect(deleteRestaurant).toHaveBeenCalledTimes(1)
    expect(deleteRestaurant).toHaveBeenCalledWith(responseFixture.id, 'owner-session')
    expect(router.currentRoute.value.path).toContain('/restaurants/')
    resolve({ ...snapshot, deleted: true })
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/'))
  })
  it('keeps the dialog and detail after a temporary failure', async () => {
    vi.mocked(deleteRestaurant).mockRejectedValue(new Error('服务暂不可用'))
    await mount()
    await confirm()
    host.querySelector<HTMLButtonElement>('.confirm-delete')!.click()
    await settle()
    expect(host.querySelector('dialog [role="alert"]')?.textContent).toContain('服务暂不可用')
    expect(router.currentRoute.value.path).toBe(`/restaurants/${responseFixture.id}`)
  })
  it('removes controls if permission is revoked at deletion time', async () => {
    vi.mocked(deleteRestaurant).mockRejectedValue(forbidden())
    await mount()
    await confirm()
    host.querySelector<HTMLButtonElement>('.confirm-delete')!.click()
    await settle()
    expect(host.querySelector('.management-buttons')).toBeNull()
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('权限已变化')
  })
  it('does not retain old permission when another tab changes accounts', async () => {
    await mount()
    vi.mocked(fetchRestaurantManagement).mockRejectedValue(forbidden())
    setAccessToken('other-account')
    await settle()
    expect(host.querySelector('.management-buttons')).toBeNull()
  })
  it('ignores a late authorization response after logout', async () => {
    let resolve!: (value: RestaurantManagement) => void
    vi.mocked(fetchRestaurantManagement).mockReturnValue(
      new Promise((done) => {
        resolve = done
      }),
    )
    await mount()
    clearAccessToken()
    resolve(snapshot)
    await settle()
    expect(host.querySelector('.management-buttons')).toBeNull()
  })
})
