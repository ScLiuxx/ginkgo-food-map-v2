import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { clearAccessToken, getAccessToken, setAccessToken } from '@/shared/api/authSession'
import { fetchCurrentUser, login, logout } from '../api/authApi'
import { useLoginStore } from './login.store'
import type { CurrentUser, LoginResponse } from './login.types'

vi.mock('../api/authApi', () => ({ login: vi.fn(), fetchCurrentUser: vi.fn(), logout: vi.fn() }))

const user: CurrentUser = { id: 42, username: 'ginkgo_user', roles: ['ROLE_USER'] }
const result: LoginResponse = {
  accessToken: 'test-session',
  tokenType: 'Bearer',
  expiresAt: '2099-01-01T00:00:00Z',
  user,
}
const request = { username: 'ginkgo_user', password: 'Ginkgo2026' }

describe('login store', () => {
  it('accepts only the backend ROLE_ADMIN role for management access', async () => {
    const store = useLoginStore()
    store.acceptSession({ ...result, user: { ...user, roles: ['ROLE_USER', 'ADMIN'] } })
    expect(store.isAdministrator).toBe(false)
    store.acceptSession({ ...result, user: { ...user, roles: ['ROLE_ADMIN'] } })
    expect(store.isAdministrator).toBe(true)
    clearAccessToken()
    expect(store.isAdministrator).toBe(false)
  })

  it('does not infer admin authority from a saved token and removes it after a role downgrade', async () => {
    setAccessToken('test-session')
    const store = useLoginStore()
    expect(store.isAdministrator).toBe(false)
    vi.mocked(fetchCurrentUser).mockResolvedValue({ ...user, roles: ['ROLE_ADMIN'] })
    await store.restore()
    expect(store.isAdministrator).toBe(true)
    vi.mocked(fetchCurrentUser).mockResolvedValue(user)
    await store.restore()
    expect(store.isAdministrator).toBe(false)
  })
  beforeEach(() => {
    clearAccessToken()
    sessionStorage.clear()
    localStorage.clear()
    setActivePinia(createPinia())
    vi.resetAllMocks()
  })

  it('persists the session across browser restarts without retaining the password', async () => {
    vi.mocked(login).mockResolvedValue(result)
    const store = useLoginStore()
    expect(await store.submit(request)).toBe(true)
    expect(store.authenticated).toBe(true)
    expect(store.user).toEqual(user)
    expect(getAccessToken()).toBe('test-session')
    expect(localStorage.getItem('ginkgo.access-token')).toBe('test-session')
    expect(sessionStorage.getItem('ginkgo.access-token')).toBeNull()
    expect(JSON.stringify(store.$state)).not.toContain(request.password)
  })

  it('shows login errors without creating a local session', async () => {
    vi.mocked(login).mockRejectedValue(new Error('用户名或密码错误'))
    const store = useLoginStore()
    expect(await store.submit(request)).toBe(false)
    expect(store.error).toBe('用户名或密码错误')
    expect(store.loggingIn).toBe(false)
    expect(store.authenticated).toBe(false)
    expect(getAccessToken()).toBeNull()
  })

  it('verifies a stored session with the server before marking it authenticated', async () => {
    setAccessToken('test-session')
    vi.mocked(fetchCurrentUser).mockResolvedValue(user)
    const store = useLoginStore()
    expect(store.authenticated).toBe(false)
    await store.restore()
    expect(fetchCurrentUser).toHaveBeenCalledWith('test-session')
    expect(store.authenticated).toBe(true)
  })

  it('preserves a session on temporary restoration failure for retry', async () => {
    setAccessToken('test-session')
    vi.mocked(fetchCurrentUser).mockRejectedValue(new Error('网络暂不可用'))
    const store = useLoginStore()
    await store.restore()
    expect(getAccessToken()).toBe('test-session')
    expect(store.authenticated).toBe(false)
    expect(store.error).toBe('网络暂不可用')
  })

  it('clears the session only after the server confirms logout', async () => {
    vi.mocked(login).mockResolvedValue(result)
    vi.mocked(logout).mockRejectedValueOnce(new Error('退出失败'))
    const store = useLoginStore()
    await store.submit(request)
    await store.signOut()
    expect(store.authenticated).toBe(true)
    expect(store.error).toBe('退出失败')
    vi.mocked(logout).mockResolvedValue()
    await store.signOut()
    expect(getAccessToken()).toBeNull()
    expect(store.user).toBeNull()
    expect(localStorage.getItem('ginkgo.access-token')).toBeNull()
    expect(store.authenticated).toBe(false)
  })

  it('cannot resurrect a logged-out user from a late restoration response', async () => {
    setAccessToken('test-session')
    let resolveRestore!: (value: CurrentUser) => void
    vi.mocked(fetchCurrentUser).mockReturnValue(
      new Promise((resolve) => {
        resolveRestore = resolve
      }),
    )
    vi.mocked(logout).mockResolvedValue()
    const store = useLoginStore()
    const restoring = store.restore()
    await store.signOut()
    resolveRestore(user)
    await restoring
    expect(store.user).toBeNull()
    expect(getAccessToken()).toBeNull()
  })

  it('does not let an old logout clear a newer login', async () => {
    setAccessToken('old-session')
    let resolveLogout!: () => void
    vi.mocked(logout).mockReturnValue(
      new Promise((resolve) => {
        resolveLogout = resolve
      }),
    )
    vi.mocked(login).mockResolvedValue(result)
    const store = useLoginStore()
    const signingOut = store.signOut()
    await store.submit(request)
    resolveLogout()
    await signingOut
    expect(getAccessToken()).toBe('test-session')
    expect(store.authenticated).toBe(true)
  })

  it('clears visible identity as soon as a protected request invalidates its token', async () => {
    vi.mocked(login).mockResolvedValue(result)
    const store = useLoginStore()
    await store.submit(request)
    clearAccessToken('test-session')
    expect(store.user).toBeNull()
    expect(store.authenticated).toBe(false)
  })

  it('discards the old identity when another tab switches accounts', async () => {
    vi.mocked(login).mockResolvedValue(result)
    const store = useLoginStore()
    await store.submit(request)
    setAccessToken('other-account-session')
    expect(store.user).toBeNull()
    expect(store.authenticated).toBe(false)
    vi.mocked(fetchCurrentUser).mockResolvedValue({
      id: 43,
      username: 'other_user',
      roles: ['ROLE_USER'],
    })
    await store.restore()
    expect(store.user?.username).toBe('other_user')
  })

  it('can restore a new token while a previous restoration is still pending', async () => {
    setAccessToken('old-session')
    let finishOld!: (value: CurrentUser) => void
    vi.mocked(fetchCurrentUser).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishOld = resolve
        }),
    )
    const store = useLoginStore()
    const pendingOld = store.restore()
    setAccessToken('new-session')
    const otherUser = { id: 43, username: 'other_user', roles: ['ROLE_USER'] }
    vi.mocked(fetchCurrentUser).mockResolvedValueOnce(otherUser)
    await store.restore()
    finishOld(user)
    await pendingOld
    expect(store.user).toEqual(otherUser)
    expect(store.restoring).toBe(false)
  })
})
