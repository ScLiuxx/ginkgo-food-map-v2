import { createApp, h, nextTick, reactive, type App } from 'vue'
import { createPinia, disposePinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useLoginStore } from './login.store'
import { clearAccessToken } from '@/shared/api/authSession'
import { httpClient } from '@/shared/api/httpClient'
import { useActivityReporting } from './useActivityReporting'

const route = reactive({ fullPath: '/', path: '/', meta: {} as { layout?: string }, matched: [{}] })
vi.mock('vue-router', () => ({ useRoute: () => route }))
vi.mock('@/shared/api/httpClient', () => ({ httpClient: { post: vi.fn() } }))
const settle = async () => {
  for (let i = 0; i < 5; i++) await nextTick()
}

describe('主动访问活跃上报', () => {
  let app: App, host: HTMLDivElement, pinia: ReturnType<typeof createPinia>
  let visibility: ReturnType<typeof vi.spyOn>
  function login(id = 1, admin = false) {
    useLoginStore().acceptSession({
      accessToken: 'report-' + id,
      tokenType: 'Bearer',
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id, username: 'test', roles: admin ? ['ROLE_USER', 'ROLE_ADMIN'] : ['ROLE_USER'] },
    })
  }
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers()
    clearAccessToken()
    pinia = createPinia()
    setActivePinia(pinia)
    route.path = '/'
    route.fullPath = '/'
    route.meta = {}
    route.matched = [{}]
    visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    vi.mocked(httpClient.post).mockResolvedValue({})
    host = document.createElement('div')
    document.body.append(host)
  })
  function mount() {
    app = createApp({
      setup() {
        useActivityReporting()
        return () => h('div')
      },
    }).use(pinia)
    app.mount(host)
  }
  afterEach(() => {
    app?.unmount()
    host.remove()
    disposePinia(pinia)
    clearAccessToken()
    vi.restoreAllMocks()
    vi.useRealTimers()
  })
  it('游客不报，登录后及路由返回上报，挂页跨日不触发心跳', async () => {
    mount()
    await settle()
    expect(httpClient.post).not.toHaveBeenCalled()
    login()
    await settle()
    expect(httpClient.post).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(86_400_000)
    expect(httpClient.post).toHaveBeenCalledTimes(1)
    route.path = '/map'
    route.fullPath = '/map'
    await settle()
    expect(httpClient.post).toHaveBeenCalledTimes(2)
    route.path = '/'
    route.fullPath = '/'
    await settle()
    expect(httpClient.post).toHaveBeenCalledTimes(3)
  })
  it('身份先恢复、首页路由稍后就绪时仍会首次上报', async () => {
    login()
    route.matched = []
    mount()
    await settle()
    expect(httpClient.post).not.toHaveBeenCalled()
    route.matched = [{}]
    await settle()
    expect(httpClient.post).toHaveBeenCalledTimes(1)
  })
  it('隐藏暂停，恢复可见主动上报，换号取消旧请求并冻结凭证', async () => {
    login()
    mount()
    await settle()
    const first = vi.mocked(httpClient.post).mock.calls[0]![2]!
    visibility.mockReturnValue('hidden')
    document.dispatchEvent(new Event('visibilitychange'))
    expect(first.signal?.aborted).toBe(true)
    route.fullPath = '/?page=2'
    await settle()
    expect(httpClient.post).toHaveBeenCalledTimes(1)
    visibility.mockReturnValue('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    await settle()
    login(2)
    await settle()
    expect(httpClient.post).toHaveBeenLastCalledWith(
      '/api/me/activity',
      undefined,
      expect.objectContaining({ headers: { Authorization: 'Bearer report-2' } }),
    )
    app.unmount()
    expect(vi.mocked(httpClient.post).mock.calls.at(-1)![2]!.signal?.aborted).toBe(true)
  })
  it('管理员、管理页与登录页不报，失败不阻止后续主动访问', async () => {
    login(1, true)
    mount()
    await settle()
    expect(httpClient.post).not.toHaveBeenCalled()
    route.meta = { layout: 'admin' }
    login(2)
    await settle()
    expect(httpClient.post).not.toHaveBeenCalled()
    route.meta = {}
    route.path = '/login'
    route.fullPath = '/login'
    await settle()
    expect(httpClient.post).not.toHaveBeenCalled()
    vi.mocked(httpClient.post).mockRejectedValueOnce(new Error('offline'))
    route.path = '/'
    route.fullPath = '/'
    await settle()
    expect(httpClient.post).toHaveBeenCalledTimes(1)
    route.path = '/map'
    route.fullPath = '/map'
    await settle()
    expect(httpClient.post).toHaveBeenCalledTimes(2)
  })
})
