/** 使用真实身份Store和界面验证写入确认、失败重读、隐私保存及会话切换清理。 */
import { createApp, h, nextTick, ref, type App, type Component } from 'vue'
import { createPinia, disposePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { beforeEach, afterEach, it, expect, vi } from 'vitest'
import { useLoginStore } from '@/modules/auth'
import { clearAccessToken } from '@/shared/api/authSession'
import { favoriteState, setFavorite, favoritePage, favoriteVisibility } from '../api/favoriteApi'
import FavoriteButton from './FavoriteButton.vue'
import RestaurantFavorites from './RestaurantFavorites.vue'
import FavoriteVisibilitySetting from '@/modules/account/ui/FavoriteVisibilitySetting.vue'
vi.mock('../api/favoriteApi', () => ({
  favoriteState: vi.fn(),
  setFavorite: vi.fn(),
  favoritePage: vi.fn(),
  favoriteVisibility: vi.fn(),
}))
let app: App, host: HTMLDivElement, pinia: ReturnType<typeof createPinia>
const id = ref('1'),
  subject = ref<string | undefined>(undefined)
const settle = async () => {
  for (let i = 0; i < 15; i++) await nextTick()
}
const page = () => ({
  userId: '11',
  records: [
    {
      restaurantId: '1',
      name: null,
      restaurantType: null,
      businessStatus: null,
      available: false,
      createdAt: '2026-11-07T00:00:00Z',
    },
  ],
  page: 1,
  pageSize: 8,
  total: 1,
  totalPages: 1,
})
beforeEach(() => {
  clearAccessToken()
  vi.resetAllMocks()
  pinia = createPinia()
  host = document.createElement('div')
  document.body.append(host)
  id.value = '1'
  subject.value = undefined
  vi.mocked(favoriteState).mockResolvedValue({ restaurantId: '1', favorited: false })
  vi.mocked(setFavorite).mockImplementation(async (restaurantId, favorited) => ({
    restaurantId,
    favorited,
  }))
  vi.mocked(favoritePage).mockResolvedValue(page())
  vi.mocked(favoriteVisibility).mockResolvedValue({ userId: '11', favoritesPublic: false })
})
afterEach(() => {
  app?.unmount()
  disposePinia(pinia)
  host.remove()
  clearAccessToken()
})
async function mount(component: Component, login = true) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<p />' } }],
  })
  await router.push('/restaurants/1')
  if (login)
    useLoginStore(pinia).acceptSession({
      accessToken: 'viewer-session',
      tokenType: 'Bearer',
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id: 11, username: '测试账号', roles: ['ROLE_USER'] },
    })
  app = createApp({ render: () => h(component, { restaurantId: id.value, userId: subject.value }) })
    .use(pinia)
    .use(router)
  app.mount(host)
  await settle()
  return router
}
const button = (label: string) =>
  [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === label)!
it('sends guests to login without querying or writing favorites', async () => {
  const router = await mount(FavoriteButton, false)
  button('☆ 收藏').click()
  await settle()
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/login'))
  expect(favoriteState).not.toHaveBeenCalled()
  expect(setFavorite).not.toHaveBeenCalled()
})
it('waits for confirmed writes, suppresses double clicks and then permits explicit cancellation', async () => {
  await mount(FavoriteButton)
  let done!: (v: { restaurantId: string; favorited: boolean }) => void
  vi.mocked(setFavorite).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        done = resolve
      }),
  )
  button('☆ 收藏').click()
  button('☆ 收藏').click()
  await settle()
  expect(setFavorite).toHaveBeenCalledTimes(1)
  expect(host.textContent).toContain('正在保存')
  done({ restaurantId: '1', favorited: true })
  await settle()
  expect(host.textContent).toContain('已收藏')
  button('★ 已收藏').click()
  await settle()
  expect(setFavorite).toHaveBeenLastCalledWith('1', false, 'viewer-session')
})
it('requires a server reread after ambiguous failure instead of blindly toggling', async () => {
  await mount(FavoriteButton)
  vi.mocked(setFavorite).mockRejectedValueOnce(new Error('网络中断'))
  button('☆ 收藏').click()
  await settle()
  expect(host.textContent).toContain('结果未确认')
  vi.mocked(favoriteState).mockResolvedValue({ restaurantId: '1', favorited: true })
  button('重新读取收藏状态').click()
  await settle()
  expect(host.textContent).toContain('★ 已收藏')
  expect(setFavorite).toHaveBeenCalledTimes(1)
})
it('drops old store responses when the target changes', async () => {
  let done!: (v: { restaurantId: string; favorited: boolean }) => void
  vi.mocked(favoriteState).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        done = resolve
      }),
  )
  await mount(FavoriteButton)
  vi.mocked(favoriteState).mockResolvedValue({ restaurantId: '2', favorited: false })
  id.value = '2'
  await settle()
  done({ restaurantId: '1', favorited: true })
  await settle()
  expect(host.querySelector('button')?.getAttribute('aria-pressed')).toBe('false')
})
it('retains unavailable own records and cancels them using the exact target', async () => {
  await mount(RestaurantFavorites)
  expect(host.textContent).toContain('店铺已失效')
  expect(host.querySelector('a')).toBeNull()
  vi.mocked(favoritePage).mockResolvedValue({ ...page(), records: [], total: 0, totalPages: 0 })
  button('取消收藏').click()
  await settle()
  expect(setFavorite).toHaveBeenCalledWith('1', false, 'viewer-session')
  expect(host.textContent).toContain('暂无可展示')
})
it('shows private-list rejection without inventing zero records and never offers cancel to others', async () => {
  subject.value = '22'
  vi.mocked(favoritePage).mockRejectedValueOnce(new Error('该用户的收藏列表仅本人可见'))
  await mount(RestaurantFavorites)
  expect(host.textContent).toContain('仅本人可见')
  expect(host.textContent).not.toContain('共 0')
  expect(
    [...host.querySelectorAll('button')].some((b) => b.textContent?.includes('取消收藏')),
  ).toBe(false)
})
it('clears private list data immediately on logout', async () => {
  await mount(RestaurantFavorites)
  clearAccessToken()
  await settle()
  expect(host.textContent).not.toContain('店铺已失效')
})
it('saves privacy only after confirmation and keeps failures retryable', async () => {
  await mount(FavoriteVisibilitySetting)
  const checkbox = host.querySelector('input')!
  expect(checkbox.checked).toBe(false)
  vi.mocked(favoriteVisibility).mockResolvedValueOnce({ userId: '11', favoritesPublic: true })
  checkbox.click()
  await settle()
  expect(checkbox.checked).toBe(true)
  expect(favoriteVisibility).toHaveBeenLastCalledWith('11', 'viewer-session', undefined, true)
  vi.mocked(favoriteVisibility).mockRejectedValueOnce(new Error('保存超时'))
  checkbox.click()
  await settle()
  expect(checkbox.disabled).toBe(true)
  expect(host.textContent).toContain('结果未确认')
})
