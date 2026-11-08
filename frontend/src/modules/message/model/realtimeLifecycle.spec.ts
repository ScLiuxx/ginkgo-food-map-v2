import { expect, it, vi } from 'vitest'
import { effectScope, reactive } from 'vue'
import { useRealtimeUnread, hasUnreadNotifications } from './useRealtimeUnread'
import { httpClient } from '@/shared/api/httpClient'
vi.mock('@/modules/auth', () => ({ useLoginStore: () => identity }))
vi.mock('@/shared/api/authSession', () => ({
  authToken: { value: 'token' },
  clearAccessToken: vi.fn(),
}))
vi.mock('@/shared/api/httpClient', () => ({ httpClient: { get: vi.fn() } }))
const identity = reactive({ authenticated: true, user: { id: 7 } })
it('退出丢弃迟到查询并中止消息流', async () => {
  let resolve!: (value: unknown) => void
  vi.mocked(httpClient.get).mockReturnValue(
    new Promise((r) => {
      resolve = r
    }) as never,
  )
  const fetchMock = vi.fn(() => new Promise(() => {}))
  vi.stubGlobal('fetch', fetchMock)
  const scope = effectScope()
  scope.run(useRealtimeUnread)
  identity.authenticated = false
  resolve({ data: { data: { userId: '7', unreadCount: 3 } } })
  await Promise.resolve()
  await Promise.resolve()
  expect(hasUnreadNotifications.value).toBe(false)
  expect((fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1].signal?.aborted).toBe(
    true,
  )
  scope.stop()
  vi.unstubAllGlobals()
})
it('历史未读显示红点，卸载清除', async () => {
  identity.authenticated = true
  vi.mocked(httpClient.get).mockResolvedValue({ data: { data: { userId: '7', unreadCount: 2 } } })
  vi.stubGlobal(
    'fetch',
    vi.fn(() => new Promise(() => {})),
  )
  const scope = effectScope()
  scope.run(useRealtimeUnread)
  await Promise.resolve()
  await Promise.resolve()
  expect(hasUnreadNotifications.value).toBe(true)
  scope.stop()
  expect(hasUnreadNotifications.value).toBe(false)
  vi.unstubAllGlobals()
})
