import { createApp, h, nextTick, ref, type App } from 'vue'
import { createPinia, disposePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { beforeEach, afterEach, it, expect, vi } from 'vitest'
import { useLoginStore } from '@/modules/auth'
import { clearAccessToken } from '@/shared/api/authSession'
import { fetchPublicProfile } from '../api/publicProfileApi'
import PublicProfile from './PublicProfile.vue'
vi.mock('../api/publicProfileApi', () => ({ fetchPublicProfile: vi.fn() }))
vi.mock('@/modules/review', () => ({
  MyReviews: {
    props: { publicUserId: String, cardLayout: Boolean },
    template: '<p data-reviews :data-card-layout="cardLayout">公开点评主体{{ publicUserId }}</p>',
  },
}))
let app: App, host: HTMLDivElement, pinia: ReturnType<typeof createPinia>
const subject = ref('22')
const section = ref<string | undefined>()
const profile = (id = '22') => ({
  userId: id,
  username: `同学${id}`,
  bio: '<script>纯文本简介</script>',
  level: 3,
  receivedLikes: 7,
})
const settle = async () => {
  for (let i = 0; i < 10; i++) await nextTick()
}
beforeEach(() => {
  clearAccessToken()
  vi.resetAllMocks()
  subject.value = '22'
  section.value = undefined
  pinia = createPinia()
  host = document.createElement('div')
  document.body.append(host)
  vi.mocked(fetchPublicProfile).mockResolvedValue(profile())
})
afterEach(() => {
  app?.unmount()
  disposePinia(pinia)
  host.remove()
  clearAccessToken()
})
async function mount(login = true) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<p />' } }],
  })
  await router.push(`/users/${subject.value}${section.value ? `/${section.value}?page=2` : ''}`)
  if (login)
    useLoginStore(pinia).acceptSession({
      accessToken: 'viewer-session',
      tokenType: 'Bearer',
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id: 11, username: '访问者', roles: ['ROLE_USER'] },
    })
  app = createApp({
    render: () => h(PublicProfile, { publicUserId: subject.value, section: section.value }),
  })
    .use(pinia)
    .use(router)
  app.mount(host)
  await settle()
  return router
}
/** 直接打开、恢复登录和换号都不能重新挂载本人主页或查询一套重复资料。 */
it.each([undefined, 'following', 'followers'])(
  '旧本人主页地址 %s 进入对应个人中心且不查询公开资料',
  async (value) => {
    subject.value = '11'
    section.value = value
    const router = await mount()
    await vi.waitFor(() =>
      expect(router.currentRoute.value.fullPath).toBe(
        value ? `/account/${value}?page=2` : '/account',
      ),
    )
    expect(fetchPublicProfile).not.toHaveBeenCalled()
    expect(host.querySelector('.account-profile, [data-reviews]')).toBeNull()
  },
)
it('restored authentication resolves a formerly unknown self-profile to account', async () => {
  subject.value = '11'
  const router = await mount(false)
  useLoginStore(pinia).acceptSession({
    accessToken: 'restored-session',
    tokenType: 'Bearer',
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id: 11, username: '本人', roles: ['ROLE_USER'] },
  })
  await settle()
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/account'))
  expect(fetchPublicProfile).not.toHaveBeenCalled()
})
it('switching to the viewed account clears old public content and returns to account', async () => {
  const router = await mount()
  useLoginStore(pinia).acceptSession({
    accessToken: 'viewed-account',
    tokenType: 'Bearer',
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id: 22, username: '本人', roles: ['ROLE_USER'] },
  })
  await settle()
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/account'))
  expect(host.querySelector('.account-profile, [data-reviews]')).toBeNull()
  expect(fetchPublicProfile).toHaveBeenCalledTimes(1)
})
it('requires login without sending protected requests or mounting reviews', async () => {
  await mount(false)
  expect(fetchPublicProfile).not.toHaveBeenCalled()
  expect(host.textContent).toContain('登录后查看个人主页')
  expect(host.querySelector('[data-reviews]')).toBeNull()
})
it('shows real public data and selected-user reviews without private fields or editing controls', async () => {
  await mount()
  expect(host.textContent).toContain('同学22')
  expect(host.textContent).toContain('Lv.3')
  expect(host.querySelector('.public-profile-likes strong')?.textContent).toBe('7')
  expect(host.querySelector('[data-reviews]')?.textContent).toContain('22')
  expect(host.querySelector('[data-reviews]')?.getAttribute('data-card-layout')).toBe('true')
  expect(host.querySelector('.profile-reviews-content')).not.toBeNull()
  expect(host.querySelector('script,textarea')).toBeNull()
  expect(host.querySelector('img')?.getAttribute('src')).toBe('/api/users/22/avatar?v=0')
  expect(host.textContent).not.toContain('邮箱')
})
it('clears the previous profile and ignores its late response after switching subjects', async () => {
  let finish!: (value: ReturnType<typeof profile>) => void
  vi.mocked(fetchPublicProfile).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  await mount()
  vi.mocked(fetchPublicProfile).mockResolvedValue(profile('33'))
  subject.value = '33'
  await settle()
  finish(profile())
  await settle()
  expect(host.textContent).toContain('同学33')
  expect(host.textContent).not.toContain('同学22')
})
it('keeps failed loads retryable instead of showing zero stats and clears data on logout', async () => {
  vi.mocked(fetchPublicProfile).mockRejectedValueOnce(new Error('主页暂时不可用'))
  await mount()
  expect(host.querySelector('[role="alert"]')?.textContent).toContain('主页暂时不可用')
  expect(host.textContent).not.toContain('Lv.1')
  host.querySelector('button')!.click()
  await settle()
  expect(host.textContent).toContain('同学22')
  clearAccessToken()
  await settle()
  expect(host.textContent).not.toContain('同学22')
  expect(host.querySelector('[data-reviews]')).toBeNull()
})
