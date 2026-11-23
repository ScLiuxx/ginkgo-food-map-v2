import { captureLinkTarget } from '@/shared/test/captureLinkTarget'
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
  type FollowKind,
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
async function mount(component: Component, kind: FollowKind = 'FOLLOWING', onTotal = vi.fn()) {
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
  app = createApp({ render: () => h(component, { userId: id.value, kind, onTotal }) })
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
  expect(host.textContent).toContain('已关注')
  button('已关注').click()
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
  button('重试').click()
  await settle()
  expect(setFollow).toHaveBeenCalledTimes(1)
  expect(host.textContent).toContain('已关注')
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
  expect(host.textContent).not.toContain('已关注')
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
const privateListError = {
  isAxiosError: true,
  response: { status: 403, data: { code: 'FOLLOW_LIST_PRIVATE', message: '该用户名单仅本人可见' } },
}
it.each(['FOLLOWING', 'FOLLOWERS'] as const)(
  '%s私密时不提供重试或假零人数，换主体及退出清理状态',
  async (kind) => {
    vi.mocked(fetchFollowPage).mockRejectedValueOnce(privateListError)
    const onTotal = vi.fn()
    await mount(FollowList, kind, onTotal)
    expect(host.querySelector('.private-content-state')?.textContent).toContain(
      kind === 'FOLLOWING' ? '对方未公开关注' : '对方未公开粉丝',
    )
    expect(host.querySelector('[role="alert"], button, nav, .follow-member')).toBeNull()
    expect(host.textContent).not.toContain('暂无可展示')
    expect(onTotal).toHaveBeenLastCalledWith(null)
    id.value = '33'
    await settle()
    expect(host.querySelector('.private-content-state')).toBeNull()
    expect(host.textContent).toContain('暂无可展示的用户')
    expect(onTotal).toHaveBeenLastCalledWith(0)
    vi.mocked(fetchFollowPage).mockRejectedValueOnce(privateListError)
    id.value = '44'
    await settle()
    expect(host.querySelector('.private-content-state')).not.toBeNull()
    clearAccessToken()
    await settle()
    expect(host.querySelector('.private-content-state')).toBeNull()
  },
)
it.each([
  new Error('网络中断'),
  {
    isAxiosError: true,
    response: { status: 403, data: { code: 'FORBIDDEN', message: '无权访问' } },
  },
  {
    isAxiosError: true,
    response: { status: 500, data: { code: 'FOLLOW_LIST_PRIVATE', message: '服务故障' } },
  },
])('名单读取故障保留重试，不误判为私密：%j', async (reason) => {
  vi.mocked(fetchFollowPage).mockRejectedValueOnce(reason)
  await mount(FollowList)
  expect(host.querySelector('.private-content-state')).toBeNull()
  expect(host.querySelector('[role="alert"]')).not.toBeNull()
  button('重新加载').click()
  await settle()
  expect(fetchFollowPage).toHaveBeenCalledTimes(2)
  expect(host.querySelector('[role="alert"]')).toBeNull()
  expect(host.textContent).toContain('暂无可展示的用户')
})
it('旧主体迟到的隐私拒绝不能覆盖新主体的公开名单', async () => {
  let rejectOld!: (reason: unknown) => void
  vi.mocked(fetchFollowPage).mockReturnValueOnce(
    new Promise((_, reject) => {
      rejectOld = reject
    }),
  )
  await mount(FollowList)
  id.value = '33'
  await settle()
  rejectOld(privateListError)
  await settle()
  expect(host.querySelector('.private-content-state')).toBeNull()
  expect(host.textContent).toContain('暂无可展示的用户')
})
/** 名单行的关系属于当前访问者，不能将“对方关注我”误当成“我已关注对方”。 */
it('follows and unfollows a fan using confirmed list data and publishes authorized totals', async () => {
  id.value = '11'
  const row = { userId: '22', username: '同学22', level: 2, following: false }
  const list = {
    userId: '11',
    kind: 'FOLLOWERS' as const,
    records: [row],
    total: 1,
    totalPages: 1,
    page: 1,
    pageSize: 8,
  }
  vi.mocked(fetchFollowPage).mockImplementation(async () => ({
    ...list,
    records: [{ ...row }],
  }))
  const onTotal = vi.fn()
  await mount(FollowList, 'FOLLOWERS', onTotal)
  expect(onTotal).toHaveBeenLastCalledWith(1)
  expect(host.querySelector('.follow-avatar')?.textContent).toBe('同')
  expect(await captureLinkTarget(app, host.querySelector('.follow-identity'))).toBe('/users/22')
  vi.mocked(setFollow).mockImplementation(async (userId, active) => {
    row.following = active
    return summary(userId, active)
  })
  button('关注').click()
  await settle()
  expect(setFollow).toHaveBeenLastCalledWith('22', true, 'session')
  expect(host.querySelector('.follow-toggle')?.textContent?.trim()).toBe('已关注')
  expect(onTotal).toHaveBeenLastCalledWith(1)
  button('已关注').click()
  await settle()
  expect(setFollow).toHaveBeenLastCalledWith('22', false, 'session')
  expect(host.querySelector('.follow-toggle')?.textContent?.trim()).toBe('关注')
  clearAccessToken()
  await settle()
  expect(onTotal).toHaveBeenLastCalledWith(null)
  expect(host.querySelector('.follow-member')).toBeNull()
})
it('rereads an uncertain follow result and never offers a self-follow action in other lists', async () => {
  vi.mocked(fetchFollowPage).mockResolvedValue({
    userId: '22',
    kind: 'FOLLOWING',
    total: 2,
    totalPages: 1,
    page: 1,
    pageSize: 8,
    records: [
      { userId: '11', username: '本人', level: null, following: false },
      { userId: '33', username: '同学33', level: 1, following: false },
    ],
  })
  await mount(FollowList)
  expect(host.querySelectorAll('.follow-toggle')).toHaveLength(1)
  expect(await captureLinkTarget(app, host.querySelector('.follow-identity'))).toBe('/account')
  vi.mocked(setFollow).mockRejectedValueOnce(new Error('保存超时'))
  button('关注').click()
  await settle()
  expect(fetchFollowPage).toHaveBeenCalledTimes(2)
  expect(host.textContent).toContain('关注结果未确认')
  expect(host.querySelector('.follow-toggle')?.textContent?.trim()).toBe('关注')
})
it('refreshes own following totals after removal and ignores a late write after switching subjects', async () => {
  id.value = '11'
  const row = { userId: '33', username: '同学33', level: 1, following: true }
  vi.mocked(fetchFollowPage).mockResolvedValue({
    userId: '11',
    kind: 'FOLLOWING',
    records: [row],
    total: 1,
    totalPages: 1,
    page: 1,
    pageSize: 8,
  })
  const onTotal = vi.fn()
  await mount(FollowList, 'FOLLOWING', onTotal)
  vi.mocked(fetchFollowPage).mockResolvedValue({
    userId: '11',
    kind: 'FOLLOWING',
    records: [],
    total: 0,
    totalPages: 0,
    page: 1,
    pageSize: 8,
  })
  button('已关注').click()
  await settle()
  expect(onTotal).toHaveBeenLastCalledWith(0)
  expect(host.querySelector('.follow-member')).toBeNull()
  vi.mocked(fetchFollowPage).mockResolvedValue({
    userId: '22',
    kind: 'FOLLOWING',
    records: [row],
    total: 1,
    totalPages: 1,
    page: 1,
    pageSize: 8,
  })
  id.value = '22'
  await settle()
  let finish!: (value: Summary) => void
  vi.mocked(setFollow).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  button('已关注').click()
  await settle()
  const reads = vi.mocked(fetchFollowPage).mock.calls.length
  id.value = '44'
  await settle()
  finish(summary('33', false))
  await settle()
  expect(fetchFollowPage).toHaveBeenCalledTimes(reads + 1)
  expect(host.textContent).not.toContain('已取消关注')
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
