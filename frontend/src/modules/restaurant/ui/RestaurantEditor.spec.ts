import { AxiosError, AxiosHeaders } from 'axios'
import { createPinia, disposePinia } from 'pinia'
import { createApp, nextTick } from 'vue'
import type { App } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import type { Router } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearAccessToken, setAccessToken } from '@/shared/api/authSession'
import {
  createRestaurant,
  fetchRestaurantCategories,
  fetchRestaurantManagement,
  updateRestaurant,
} from '../api/restaurantApi'
import type { RestaurantManagement } from '../api/restaurantApi'
import { categoryFixtures, responseFixture } from '../api/restaurant.test-data'
import RestaurantEditor from './RestaurantEditor.vue'

vi.mock('../api/restaurantApi', () => ({
  createRestaurant: vi.fn(),
  updateRestaurant: vi.fn(),
  fetchRestaurantCategories: vi.fn(),
  fetchRestaurantManagement: vi.fn(),
}))
const snapshot: RestaurantManagement = {
  restaurant: { ...responseFixture, latitude: 30.7544, longitude: 103.9312 },
  deleted: false,
}
const failure = (status: number, data: object = {}) =>
  new AxiosError('服务端失败', '', undefined, undefined, {
    status,
    statusText: '',
    data,
    headers: new AxiosHeaders(),
    config: { headers: new AxiosHeaders() },
  })

/** 真实路由与DOM表单覆盖完整替换、权限失败、会话变化及不自动重试写入。 */
describe('restaurant maintenance form', () => {
  let app: App
  let host: HTMLDivElement
  let router: Router
  let pinia: ReturnType<typeof createPinia>
  const settle = async () => {
    for (let i = 0; i < 8; i++) await nextTick()
  }
  beforeEach(() => {
    vi.resetAllMocks()
    setAccessToken('owner-session')
    vi.mocked(fetchRestaurantCategories).mockResolvedValue(categoryFixtures)
    vi.mocked(fetchRestaurantManagement).mockResolvedValue(snapshot)
    vi.mocked(createRestaurant).mockResolvedValue(snapshot)
    vi.mocked(updateRestaurant).mockResolvedValue(snapshot)
    host = document.createElement('div')
    document.body.append(host)
    pinia = createPinia()
    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: '/admin/restaurants/new',
          component: RestaurantEditor,
          props: { admin: true },
        },
        { path: '/admin/restaurants', component: { template: '<div />' } },
        { path: '/restaurants/new', component: RestaurantEditor },
        {
          path: '/restaurants/:id/edit',
          component: RestaurantEditor,
          props: true,
        },
        ...['/', '/restaurants/:id', '/login'].map((path) => ({
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
    vi.restoreAllMocks()
  })
  async function mount(path = '/restaurants/new') {
    await router.push(path)
    app = createApp(RouterView).use(pinia).use(router)
    app.mount(host)
    await settle()
  }
  async function field(id: string, value: string | boolean) {
    const input = host.querySelector<HTMLInputElement>(`#restaurant-${id}`)!
    if (typeof value === 'boolean') input.checked = value
    else input.value = value
    input.dispatchEvent(
      new Event(input.tagName === 'SELECT' || typeof value === 'boolean' ? 'change' : 'input', {
        bubbles: true,
      }),
    )
    await settle()
  }
  async function fill() {
    await field('name', '新建测试店')
    await field('category', 'HOT_POT')
    await field('latitude', '30.7544')
    await field('longitude', '103.9312')
    await field('location-confirmed', true)
  }
  async function submit() {
    host
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await settle()
  }
  it('offers a login return path and makes no management calls anonymously', async () => {
    clearAccessToken()
    await mount()
    expect(host.querySelector('form')).toBeNull()
    expect(host.querySelector('a[href^="/login"]')?.getAttribute('href')).toContain(
      'redirect=/restaurants/new',
    )
    expect(fetchRestaurantManagement).not.toHaveBeenCalled()
  })
  it('直接维护已有食堂时隐藏分类，并按现有类型提交空分类', async () => {
    vi.mocked(fetchRestaurantManagement).mockResolvedValue({
      ...snapshot,
      restaurant: {
        ...snapshot.restaurant,
        restaurantType: 'CANTEEN',
        categoryCode: null,
        categoryName: null,
      },
    })
    await mount('/restaurants/19/edit')
    expect(host.querySelector('#restaurant-category')).toBeNull()
    await field('location-confirmed', true)
    await submit()
    expect(updateRestaurant).toHaveBeenCalledWith(
      '19',
      expect.objectContaining({ categoryCode: null }),
      'owner-session',
    )
  })

  it('validates location and required fields before making requests', async () => {
    await mount()
    await submit()
    expect(createRestaurant).not.toHaveBeenCalled()
    expect(host.querySelector('#error-location')?.textContent).toContain('确认')
  })

  it('管理端直接新建成功后返回商户管理并保留新店ID', async () => {
    await mount('/admin/restaurants/new')
    expect(host.textContent).toContain('保存后直接公开生效')
    await fill()
    await submit()
    expect(createRestaurant).toHaveBeenCalledTimes(1)
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/admin/restaurants'))
    expect(router.currentRoute.value.query.saved).toBe(snapshot.restaurant.id)
  })

  it('removes stale field feedback when the user corrects the input', async () => {
    await mount()
    await submit()
    await fill()
    expect(host.querySelector('.field-error')).toBeNull()
    expect(host.querySelector('.editor-notice[role="alert"]')).toBeNull()
  })
  it('creates using all fields then navigates only after success', async () => {
    await mount()
    await fill()
    await submit()
    expect(createRestaurant).toHaveBeenCalledWith(
      expect.objectContaining({
        name: '新建测试店',
        pricePerPerson: null,
        address: null,
        tags: [],
        locationConfirmed: true,
      }),
      'owner-session',
    )
    await vi.waitFor(() =>
      expect(router.currentRoute.value.path).toBe(`/restaurants/${responseFixture.id}`),
    )
  })
  it('prevents duplicate writes while a request is pending', async () => {
    let resolve!: (value: RestaurantManagement) => void
    vi.mocked(createRestaurant).mockReturnValue(
      new Promise((done) => {
        resolve = done
      }),
    )
    await mount()
    await fill()
    await submit()
    await submit()
    expect(createRestaurant).toHaveBeenCalledTimes(1)
    expect(host.querySelector('fieldset')?.disabled).toBe(true)
    resolve(snapshot)
    await settle()
  })
  it('fills editable fields and sends a complete PUT without versions', async () => {
    await mount(`/restaurants/${responseFixture.id}/edit`)
    expect(host.querySelector<HTMLInputElement>('#restaurant-location-confirmed')!.checked).toBe(
      false,
    )
    await field('name', '修改后')
    await field('location-confirmed', true)
    await submit()
    expect(updateRestaurant).toHaveBeenCalledWith(
      responseFixture.id,
      expect.objectContaining({
        name: '修改后',
        categoryCode: 'HOT_POT',
        businessStatus: 'OPERATING',
        tags: ['联调模拟'],
        address: null,
      }),
      'owner-session',
    )
    expect(vi.mocked(updateRestaurant).mock.calls[0]?.[1]).not.toHaveProperty('rowVersion')
  })
  it.each([403, 404])('does not show an empty editor on management HTTP %s', async (status) => {
    vi.mocked(fetchRestaurantManagement).mockRejectedValue(failure(status))
    await mount('/restaurants/1/edit')
    expect(host.querySelector('form')).toBeNull()
    expect(host.querySelector('[role="alert"]')).not.toBeNull()
  })
  it('allows retaining an existing disabled category but hides other disabled choices', async () => {
    vi.mocked(fetchRestaurantCategories).mockResolvedValue([
      { ...categoryFixtures[0]!, enabled: false },
      { code: 'OTHER', name: '其他', sortOrder: 2, enabled: false },
    ])
    await mount('/restaurants/1/edit')
    expect(host.querySelector('option[value="HOT_POT"]')?.textContent).toContain('可保留')
    expect(host.querySelector('option[value="OTHER"]')).toBeNull()
  })
  it('retains inputs and maps server field errors for manual retry', async () => {
    vi.mocked(createRestaurant).mockRejectedValue(
      failure(400, {
        message: '输入无效',
        data: [{ field: 'name', message: '名称不符合规则' }],
      }),
    )
    await mount()
    await fill()
    await submit()
    expect(host.querySelector<HTMLInputElement>('#restaurant-name')?.value).toBe('新建测试店')
    expect(host.querySelector('#error-name')?.textContent).toBe('名称不符合规则')
    expect(createRestaurant).toHaveBeenCalledTimes(1)
  })
  it('treats POST timeout as an unknown result and never auto retries', async () => {
    vi.mocked(createRestaurant).mockRejectedValue(new AxiosError('timeout', 'ECONNABORTED'))
    await mount()
    await fill()
    await submit()
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('先检查')
    expect(createRestaurant).toHaveBeenCalledTimes(1)
  })
  it('requires position reconfirmation after changing either coordinate', async () => {
    await mount()
    await fill()
    await field('longitude', '104')
    expect(host.querySelector<HTMLInputElement>('#restaurant-location-confirmed')?.checked).toBe(
      false,
    )
  })
  it('locks an old account draft and ignores its pending successful response', async () => {
    let resolve!: (value: RestaurantManagement) => void
    vi.mocked(createRestaurant).mockReturnValue(
      new Promise((done) => {
        resolve = done
      }),
    )
    await mount()
    await fill()
    await submit()
    setAccessToken('different-account')
    await settle()
    resolve(snapshot)
    await settle()
    expect(host.querySelector('form')).toBeNull()
    expect(host.textContent).toContain('登录状态已变化')
    expect(router.currentRoute.value.path).toBe('/restaurants/new')
  })
  it('asks before leaving unsaved input and permits cancel when confirmed', async () => {
    await mount()
    await fill()
    const confirm = vi.spyOn(globalThis, 'confirm').mockReturnValue(false)
    await router.push('/')
    expect(router.currentRoute.value.path).toBe('/restaurants/new')
    confirm.mockReturnValue(true)
    await router.push('/')
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('protects unsaved input when the editor route changes to another restaurant', async () => {
    await mount('/restaurants/1/edit')
    await field('name', '尚未保存')
    vi.spyOn(globalThis, 'confirm').mockReturnValue(false)
    await router.push('/restaurants/2/edit')
    expect(router.currentRoute.value.path).toBe('/restaurants/1/edit')
    expect(host.querySelector<HTMLInputElement>('#restaurant-name')?.value).toBe('尚未保存')
  })
})
