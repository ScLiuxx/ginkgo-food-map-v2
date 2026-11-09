import { createApp, h, nextTick, type App } from 'vue'
import { createPinia, disposePinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearAccessToken } from '@/shared/api/authSession'
import { useLoginStore } from '@/modules/auth'
import { fetchFollowSummary, followPrivacy } from '@/modules/community/api/followsApi'
import { favoritePage, favoriteVisibility } from '@/modules/restaurant/api/favoriteApi'
import { fetchOwnGrowth, type GrowthSummary } from '../api/growthApi'
import AccountCenter from './AccountCenter.vue'
import { fetchOwnProfile, updateOwnBio, type OwnProfile } from '../api/profileApi'
import { fetchMyRequestPage } from '@/modules/restaurant/api/myRestaurantRequestsApi'

vi.mock('@/modules/community/api/followsApi', () => ({
  fetchFollowSummary: vi.fn(),
  followPrivacy: vi.fn(),
}))
vi.mock('@/modules/restaurant/api/favoriteApi', async (original) => ({
  ...(await original<typeof import('@/modules/restaurant/api/favoriteApi')>()),
  favoritePage: vi.fn(),
  favoriteVisibility: vi.fn(),
}))
vi.mock('../api/growthApi', () => ({ fetchOwnGrowth: vi.fn() }))
vi.mock('../api/profileApi', () => ({ fetchOwnProfile: vi.fn(), updateOwnBio: vi.fn() }))
vi.mock('@/modules/restaurant/api/myRestaurantRequestsApi', async (original) => ({
  ...(await original<typeof import('@/modules/restaurant/api/myRestaurantRequestsApi')>()),
  fetchMyRequestPage: vi.fn(),
}))
const details = (
  userId = '42',
  bio = userId === '42' ? '喜欢面食' : '另一位同学的简介',
): OwnProfile => ({
  userId,
  username: userId === '42' ? '测试同学' : '另一位同学',
  maskedEmail: 't***@std.uestc.edu.cn',
  bio,
})
const summary: GrowthSummary = {
  userId: '42',
  experience: 125,
  level: 2,
  currentLevelThreshold: 100,
  nextLevelThreshold: 400,
}

/** 使用真实身份Store验证异步隔离、隐私边界和待开放能力，不向业务库写入测试资料。 */
describe('个人中心', () => {
  const showDescriptor = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal')
  const closeDescriptor = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'close')
  let app: App | undefined
  let host: HTMLDivElement
  let pinia: ReturnType<typeof createPinia>
  beforeEach(() => {
    clearAccessToken()
    vi.resetAllMocks()
    vi.mocked(fetchFollowSummary).mockResolvedValue({
      userId: '42',
      followingCount: 2,
      followersCount: 3,
      following: false,
    })
    vi.mocked(followPrivacy).mockResolvedValue({
      userId: '42',
      followingPublic: false,
      followersPublic: false,
    })
    vi.mocked(favoritePage).mockResolvedValue({
      userId: '42',
      records: [],
      total: 0,
      totalPages: 0,
      page: 1,
      pageSize: 8,
    })
    vi.mocked(favoriteVisibility).mockResolvedValue({ userId: '42', favoritesPublic: false })
    vi.mocked(fetchOwnGrowth).mockResolvedValue(summary)
    vi.mocked(fetchOwnProfile).mockImplementation(async (userId) => details(userId))
    vi.mocked(updateOwnBio).mockImplementation(async (userId, _token, bio) => details(userId, bio))
    vi.mocked(fetchMyRequestPage).mockResolvedValue({
      userId: '42',
      records: [],
      counts: { ALL: 0, PENDING: 0, APPROVED: 0, REJECTED: 0 },
      total: 0,
      totalPages: 0,
      page: 1,
      pageSize: 10,
    })
    pinia = createPinia()
    setActivePinia(pinia)
    host = document.createElement('div')
    document.body.append(host)
    // jsdom不实现原生dialog方法，夹具只模拟开关；真实焦点与模态行为由浏览器检查。
    Object.defineProperties(HTMLDialogElement.prototype, {
      showModal: {
        configurable: true,
        value() {
          this.open = true
        },
      },
      close: {
        configurable: true,
        value() {
          this.open = false
          this.dispatchEvent(new Event('close'))
        },
      },
    })
  })
  afterEach(() => {
    app?.unmount()
    app = undefined
    disposePinia(pinia)
    host.remove()
    clearAccessToken()
    for (const [name, descriptor] of [
      ['showModal', showDescriptor],
      ['close', closeDescriptor],
    ] as const) {
      if (descriptor) Object.defineProperty(HTMLDialogElement.prototype, name, descriptor)
      else Reflect.deleteProperty(HTMLDialogElement.prototype, name)
    }
  })
  function signIn(id = 42, username = '测试同学') {
    useLoginStore().acceptSession({
      accessToken: `session-${id}`,
      tokenType: 'Bearer',
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id, username, roles: ['ROLE_USER'] },
    })
  }
  async function mount(path = '/account', signedIn = true) {
    if (signedIn) signIn()
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: '/account/:section(reviews|favorites|contributions|settings|growth|following|followers)?',
          component: AccountCenter,
          props: true,
        },
        { path: '/users/:publicUserId([1-9]\\d*)', component: AccountCenter, props: true },
        ...['/', '/login', '/password-reset', '/restaurants/submit'].map((path) => ({
          path,
          component: { template: '<p>目标页面</p>' },
        })),
      ],
    })
    await router.push(path)
    await router.isReady()
    app = createApp({ render: () => h(RouterView) })
      .use(pinia)
      .use(router)
    app.mount(host)
    await nextTick()
    return router
  }

  it('本人展示真实等级、用户名首字和四个入口，不使用虚构统计或暂缓功能', async () => {
    await mount()
    await vi.waitFor(() => expect(host.textContent).toContain('125 / 400 经验'))
    expect(host.querySelectorAll('.account-tools > a')).toHaveLength(4)
    expect(host.querySelector('.level-badge')?.textContent).toBe('Lv.2')
    expect(host.querySelector('progress')?.value).toBeCloseTo((25 / 300) * 100)
    expect(host.querySelector('.avatar, img')).toBeNull()
    expect(host.querySelector('.profile-avatar')?.textContent).toBe('测')
    expect(host.querySelectorAll('.profile-statistics strong')[0]?.textContent).toBe('2')
    expect(host.textContent).not.toMatch(/私信|最近浏览|草稿箱|会员|称号|我的举报/)
  })

  it('查询失败显示重试，不能伪装为零经验或Lv.1', async () => {
    vi.mocked(fetchOwnGrowth).mockRejectedValueOnce(new Error('暂时不可用'))
    await mount()
    await vi.waitFor(() =>
      expect(host.querySelector('[role="alert"]')?.textContent).toContain('暂时不可用'),
    )
    expect(host.querySelector('.level-badge, progress')).toBeNull()
    host.querySelector<HTMLButtonElement>('.inline-link')!.click()
    await vi.waitFor(() => expect(host.textContent).toContain('125 / 400 经验'))
  })

  it('换号后清除旧数据，并拒绝旧请求晚到覆盖新账号', async () => {
    const requests: {
      token: string
      signal: AbortSignal
      resolve: (value: GrowthSummary) => void
    }[] = []
    vi.mocked(fetchOwnGrowth).mockImplementation(
      (token, signal) => new Promise((resolve) => requests.push({ token, signal, resolve })),
    )
    await mount()
    const old = requests[0]!
    signIn(88, '另一位同学')
    await nextTick()
    const current = requests.at(-1)!
    expect(current.token).toBe('session-88')
    expect(old.signal.aborted).toBe(true)
    current.resolve({
      ...summary,
      userId: '88',
      experience: 5,
      level: 1,
      currentLevelThreshold: 0,
      nextLevelThreshold: 100,
    })
    await vi.waitFor(() => expect(host.textContent).toContain('5 / 100 经验'))
    old.resolve(summary)
    await nextTick()
    expect(host.textContent).not.toContain('125 / 400 经验')
    expect(host.textContent).not.toContain('测试同学')
    clearAccessToken()
    await nextTick()
    expect(host.querySelector('.growth-card, .level-badge')).toBeNull()
  })

  it('游客进入个人主页只显示登录入口，不请求成长或泄漏资料', async () => {
    await mount('/users/42', false)
    expect(fetchOwnGrowth).not.toHaveBeenCalled()
    expect(fetchOwnProfile).not.toHaveBeenCalled()
    expect(host.textContent).toContain('登录后查看个人主页')
    const href = host.querySelector<HTMLAnchorElement>('.account-primary')!.href
    expect(new URL(href).searchParams.get('redirect')).toBe('/users/42')
    expect(host.querySelector('.account-profile, .account-tools, .growth-card')).toBeNull()
  })

  it('查看他人时不查询本人经验或将本人资料冒充对方，收藏仍为待开放状态', async () => {
    await mount('/users/99')
    expect(fetchOwnGrowth).not.toHaveBeenCalled()
    expect(fetchOwnProfile).not.toHaveBeenCalled()
    expect(host.querySelector('.account-profile, .growth-card')).toBeNull()
    expect(host.textContent).not.toContain('测试同学')
    host.querySelectorAll<HTMLButtonElement>('.profile-tabs button')[1]!.click()
    await nextTick()
    expect(host.querySelector('#profile-content')?.textContent).toContain('只有本人选择公开后')
    expect(host.querySelector('.contribution-toolbar')).toBeNull()
  })

  it('收藏接入本人列表，申请页面保留投稿入口', async () => {
    const router = await mount('/account/favorites')
    expect(fetchOwnGrowth).not.toHaveBeenCalled()
    expect(host.querySelector('.favorites-list')).not.toBeNull()
    expect(host.textContent).not.toContain('暂无收藏')
    await router.push('/account/contributions')
    await nextTick()
    expect(host.querySelector('.contributions-toolbar a')?.getAttribute('href')).toBe(
      '/restaurants/submit',
    )
    expect(host.textContent).toContain('仅你和有权处理的管理员可见')
    expect(fetchMyRequestPage).toHaveBeenCalledWith(
      'ALL',
      1,
      '42',
      'session-42',
      expect.any(AbortSignal),
    )
  })

  it('最高等级不虚构下一级；读取身份不匹配时拒绝展示', async () => {
    vi.mocked(fetchOwnGrowth).mockResolvedValueOnce({
      ...summary,
      experience: 1000,
      level: 4,
      currentLevelThreshold: 1000,
      nextLevelThreshold: null,
    })
    const router = await mount('/account/growth')
    await vi.waitFor(() => expect(host.textContent).toContain('已达到当前最高等级'))
    expect(host.querySelector('progress')?.value).toBe(100)
    expect(host.textContent).not.toContain('Lv.5')
    vi.mocked(fetchOwnGrowth).mockResolvedValueOnce({ ...summary, userId: '99' })
    await router.push('/account')
    await vi.waitFor(() => expect(host.textContent).toContain('成长资料与当前账号不一致'))
    expect(host.querySelector('.level-badge')).toBeNull()
  })

  it('设置保留隐私待开放边界，取消简介编辑清除草稿', async () => {
    await mount('/account/settings')
    const show = vi.spyOn(HTMLDialogElement.prototype, 'showModal').mockImplementation(function (
      this: HTMLDialogElement,
    ) {
      this.open = true
    })
    const close = vi.spyOn(HTMLDialogElement.prototype, 'close').mockImplementation(function (
      this: HTMLDialogElement,
    ) {
      this.open = false
      this.dispatchEvent(new Event('close'))
    })
    await vi.waitFor(() =>
      expect(host.querySelector<HTMLButtonElement>('.bio-entry')!.disabled).toBe(false),
    )
    host.querySelector<HTMLButtonElement>('.bio-entry')!.click()
    await nextTick()
    expect(show).toHaveBeenCalledOnce()
    expect(host.querySelector<HTMLButtonElement>('.bio-dialog .account-primary')?.disabled).toBe(
      true,
    )
    expect(host.querySelectorAll('.follow-privacy input')).toHaveLength(2)
    expect(host.querySelector('.favorite-privacy input')).not.toBeNull()
    expect(host.textContent).not.toMatch(/账号申诉|更换邮箱|注销账号|登录设备/)
    const input = host.querySelector<HTMLTextAreaElement>('#account-bio')!
    input.value = '未保存简介'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    host.querySelector<HTMLButtonElement>('.bio-dialog .account-secondary')!.click()
    await nextTick()
    expect(input.value).toBe('')
    show.mockRestore()
    close.mockRestore()
  })

  async function openEditor() {
    await vi.waitFor(() =>
      expect(host.querySelector<HTMLButtonElement>('.bio-entry')!.disabled).toBe(false),
    )
    host.querySelector<HTMLButtonElement>('.bio-entry')!.click()
    await nextTick()
    return host.querySelector<HTMLTextAreaElement>('#account-bio')!
  }
  async function fillBio(input: HTMLTextAreaElement, value: string) {
    input.value = value
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
  }

  it('加载真实脱敏邮箱和简介，保存后立即更新并在我的页面显示', async () => {
    const router = await mount('/account/settings')
    const input = await openEditor()
    expect(input.value).toBe('喜欢面食')
    expect(host.querySelector('.masked-email')?.textContent).toBe('t***@std.uestc.edu.cn')
    await fillBio(input, '  新的用餐简介  ')
    host.querySelector<HTMLButtonElement>('.bio-dialog .account-primary')!.click()
    await vi.waitFor(() =>
      expect(host.querySelector('.profile-save-notice')?.textContent).toContain('已更新'),
    )
    expect(updateOwnBio).toHaveBeenCalledExactlyOnceWith(
      '42',
      'session-42',
      '新的用餐简介',
      expect.any(AbortSignal),
    )
    await router.push('/account')
    await nextTick()
    expect(host.querySelector('.profile-bio')?.textContent).toBe('新的用餐简介')
    expect(host.querySelector('.masked-email')).toBeNull()
  })

  it('读取失败不伪造空简介或邮箱，重新加载后恢复可编辑', async () => {
    vi.mocked(fetchOwnProfile).mockRejectedValueOnce(new Error('资料服务故障'))
    await mount('/account/settings')
    await vi.waitFor(() =>
      expect(host.querySelector('.profile-read-error')?.textContent).toContain('资料服务故障'),
    )
    expect(host.querySelector<HTMLButtonElement>('.bio-entry')!.disabled).toBe(true)
    expect(host.querySelector('.bio-preview')).toBeNull()
    host.querySelector<HTMLButtonElement>('.profile-read-error button')!.click()
    await vi.waitFor(() =>
      expect(host.querySelector<HTMLButtonElement>('.bio-entry')!.disabled).toBe(false),
    )
    expect(host.querySelector('.bio-preview')?.textContent).toBe('喜欢面食')
  })

  it('限制联系方式及长度，100个emoji可保存，留空可清除', async () => {
    await mount('/account/settings')
    const input = await openEditor()
    await fillBio(input, '微信：food123')
    expect(host.querySelector<HTMLButtonElement>('.bio-dialog .account-primary')!.disabled).toBe(
      true,
    )
    expect(host.querySelector('.bio-validation-error')?.textContent).toContain('不能包含')
    await fillBio(input, '🥟'.repeat(100))
    expect(host.querySelector('.bio-label')?.textContent).toContain('100 / 100')
    expect(host.querySelector<HTMLButtonElement>('.bio-dialog .account-primary')!.disabled).toBe(
      false,
    )
    await fillBio(input, '中'.repeat(101))
    expect(host.querySelector<HTMLButtonElement>('.bio-dialog .account-primary')!.disabled).toBe(
      true,
    )
    expect(updateOwnBio).not.toHaveBeenCalled()
    await fillBio(input, '')
    host.querySelector<HTMLButtonElement>('.bio-dialog .account-primary')!.click()
    await vi.waitFor(() =>
      expect(host.querySelector('.profile-save-notice')?.textContent).toContain('已更新'),
    )
    expect(vi.mocked(updateOwnBio).mock.calls[0]?.[2]).toBe('')
    expect(host.querySelector('.bio-preview')?.textContent).toBe('还没有填写个人简介')
  })

  it('保存失败保留草稿与弹窗，重试成功才显示已更新', async () => {
    vi.mocked(updateOwnBio).mockRejectedValueOnce(new Error('保存尚未确认'))
    await mount('/account/settings')
    const input = await openEditor()
    await fillBio(input, '保留这份草稿')
    host.querySelector<HTMLButtonElement>('.bio-dialog .account-primary')!.click()
    await vi.waitFor(() =>
      expect(host.querySelector('.bio-validation-error')?.textContent).toContain('保存尚未确认'),
    )
    expect(input.value).toBe('保留这份草稿')
    expect(host.querySelector<HTMLDialogElement>('.bio-dialog')!.open).toBe(true)
    expect(host.querySelector('.profile-save-notice')).toBeNull()
    host.querySelector<HTMLButtonElement>('.bio-dialog .account-primary')!.click()
    await vi.waitFor(() =>
      expect(host.querySelector('.profile-save-notice')?.textContent).toContain('已更新'),
    )
    expect(host.querySelector<HTMLDialogElement>('.bio-dialog')!.open).toBe(false)
  })

  it('保存期间不重复提交，换号后旧PATCH回执不能覆盖新账号', async () => {
    let finish!: (value: OwnProfile) => void
    vi.mocked(updateOwnBio).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    await mount('/account/settings')
    const input = await openEditor()
    await fillBio(input, '旧账号未确认的简介')
    const save = host.querySelector<HTMLButtonElement>('.bio-dialog .account-primary')!
    save.click()
    save.click()
    await nextTick()
    expect(updateOwnBio).toHaveBeenCalledOnce()
    expect(host.querySelector<HTMLButtonElement>('.bio-dialog .account-secondary')!.disabled).toBe(
      true,
    )
    signIn(88, '另一位同学')
    await vi.waitFor(() =>
      expect(host.querySelector('.bio-preview')?.textContent).toBe('另一位同学的简介'),
    )
    finish(details('42', '旧账号未确认的简介'))
    await nextTick()
    expect(host.querySelector('.bio-preview')?.textContent).toBe('另一位同学的简介')
    expect(host.querySelector('.profile-save-notice')).toBeNull()
    expect(host.querySelector<HTMLDialogElement>('.bio-dialog')!.open).toBe(false)
  })

  it('换号取消旧GET，晚到资料不会恢复旧简介或脱敏邮箱', async () => {
    const pending: { user: string; signal: AbortSignal; resolve: (value: OwnProfile) => void }[] =
      []
    vi.mocked(fetchOwnProfile).mockImplementation(
      (user, _token, signal) => new Promise((resolve) => pending.push({ user, signal, resolve })),
    )
    await mount('/account/settings')
    const old = pending[0]!
    signIn(88, '另一位同学')
    await nextTick()
    expect(old.signal.aborted).toBe(true)
    pending.at(-1)!.resolve({ ...details('88'), maskedEmail: 'n***@std.uestc.edu.cn' })
    await vi.waitFor(() =>
      expect(host.querySelector('.masked-email')?.textContent).toBe('n***@std.uestc.edu.cn'),
    )
    old.resolve(details('42', '旧账号私有简介'))
    await nextTick()
    expect(host.textContent).not.toContain('旧账号私有简介')
    expect(host.querySelector('.masked-email')?.textContent).toBe('n***@std.uestc.edu.cn')
    clearAccessToken()
    await nextTick()
    expect(host.querySelector('.masked-email, .bio-preview')).toBeNull()
  })
})
