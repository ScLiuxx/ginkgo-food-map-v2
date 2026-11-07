import { createApp, h, nextTick, ref, type App, type Component } from 'vue'
import { createPinia, disposePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { beforeEach, afterEach, it, expect, vi } from 'vitest'
import { useLoginStore } from '@/modules/auth'
import { clearAccessToken } from '@/shared/api/authSession'
import {
  fetchFollowSummary,
  setFollow,
  fetchFollowPage,
  followPrivacy,
  type FollowSummary as Summary,
} from '../api/followsApi'
import FollowSummary from './FollowSummary.vue'
import FollowList from './FollowList.vue'
import FollowPrivacySetting from './FollowPrivacySetting.vue'
vi.mock('../api/followsApi', () => ({
  fetchFollowSummary: vi.fn(),
  setFollow: vi.fn(),
  fetchFollowPage: vi.fn(),
  followPrivacy: vi.fn(),
}))
/** 真实身份Store下验证确认写入、请求隔离、名单授权错误及隐私设置。 */
let app: App, host: HTMLDivElement, pinia: ReturnType<typeof createPinia>
const id = ref('22'),
  summary = (userId = '22', following = false): Summary => ({
    userId,
    followingCount: 2,
    followersCount: following ? 4 : 3,
    following,
  })
const settle = async () => {
  for (let i = 0; i < 16; i++) await nextTick()
}
beforeEach(() => {
  clearAccessToken()
  vi.resetAllMocks()
  pinia = createPinia()
  host = document.createElement('div')
  document.body.append(host)
  id.value = '22'
  vi.mocked(fetchFollowSummary).mockResolvedValue(summary())
  vi.mocked(setFollow).mockImplementation(async (userId, active) => summary(userId, active))
  vi.mocked(fetchFollowPage).mockResolvedValue({
    userId: '22',
    kind: 'FOLLOWING',
    records: [],
    total: 0,
    totalPages: 0,
    page: 1,
    pageSize: 8,
  })
  vi.mocked(followPrivacy).mockResolvedValue({
    userId: '11',
    followingPublic: false,
    followersPublic: false,
  })
})
afterEach(() => {
  app?.unmount()
  disposePinia(pinia)
  host.remove()
  clearAccessToken()
})
async function mount(component: Component) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<p />' } }],
  })
  await router.push('/users/22')
  useLoginStore(pinia).acceptSession({
    accessToken: 'session',
    tokenType: 'Bearer',
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id: 11, username: '本人', roles: ['ROLE_USER'] },
  })
  app = createApp({ render: () => h(component, { userId: id.value, kind: 'FOLLOWING' }) })
    .use(pinia)
    .use(router)
  app.mount(host)
  await settle()
}
const button = (label: string) =>
  [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === label)!
it('shows real public counts and confirms follow before updating', async () => {
  await mount(FollowSummary)
  expect(host.textContent).toContain('3 粉丝')
  button('关注').click()
  await settle()
  expect(setFollow).toHaveBeenCalledWith('22', true, 'session')
  expect(host.textContent).toContain('已关注 · 取消')
  button('已关注 · 取消').click()
  await settle()
  expect(setFollow).toHaveBeenLastCalledWith('22', false, 'session')
})
it('requires rereading after an ambiguous write without repeating the operation', async () => {
  await mount(FollowSummary)
  vi.mocked(setFollow).mockRejectedValueOnce(new Error('保存超时'))
  button('关注').click()
  await settle()
  expect(host.textContent).toContain('结果未确认')
  vi.mocked(fetchFollowSummary).mockResolvedValue(summary('22', true))
  button('重新读取关注状态').click()
  await settle()
  expect(setFollow).toHaveBeenCalledTimes(1)
  expect(host.textContent).toContain('已关注 · 取消')
})
it('ignores the old target response after navigation and clears on logout', async () => {
  let finish!: (value: Summary) => void
  vi.mocked(fetchFollowSummary).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  await mount(FollowSummary)
  vi.mocked(fetchFollowSummary).mockResolvedValue(summary('33', false))
  id.value = '33'
  await settle()
  finish(summary('22', true))
  await settle()
  expect(host.textContent).not.toContain('已关注 · 取消')
  clearAccessToken()
  await settle()
  expect(host.textContent).not.toContain('3 粉丝')
})
it('never offers a self-follow button', async () => {
  id.value = '11'
  vi.mocked(fetchFollowSummary).mockResolvedValue(summary('11', false))
  await mount(FollowSummary)
  expect(host.querySelector('button')).toBeNull()
})
it('keeps private list errors distinct from an empty list', async () => {
  vi.mocked(fetchFollowPage).mockRejectedValueOnce(new Error('该用户名单仅本人可见'))
  await mount(FollowList)
  expect(host.querySelector('[role="alert"]')?.textContent).toContain('仅本人可见')
  expect(host.textContent).not.toContain('共 0 人')
})
it('saves one privacy switch while preserving the other and blocks uncertain state', async () => {
  await mount(FollowPrivacySetting)
  const inputs = host.querySelectorAll('input')
  vi.mocked(followPrivacy).mockResolvedValueOnce({
    userId: '11',
    followingPublic: true,
    followersPublic: false,
  })
  inputs[0]!.click()
  await settle()
  expect(followPrivacy).toHaveBeenLastCalledWith('11', 'session', undefined, {
    followingPublic: true,
    followersPublic: false,
  })
  expect(inputs[1]!.checked).toBe(false)
  vi.mocked(followPrivacy).mockRejectedValueOnce(new Error('超时'))
  inputs[1]!.click()
  await settle()
  expect(inputs[0]!.disabled).toBe(true)
  expect(host.textContent).toContain('结果未确认')
})
