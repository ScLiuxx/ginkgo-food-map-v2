import { ref, readonly, watch, onScopeDispose } from 'vue'
import { useLoginStore } from '@/modules/auth'
import { authToken, clearAccessToken } from '@/shared/api/authSession'
import { httpClient } from '@/shared/api/httpClient'

const revision = ref(0)
export const notificationRevision = readonly(revision)
const unread = ref(false)
export const hasUnreadNotifications = readonly(unread)

/** 按行解析SSE，支持网络分片和CRLF；仅消费刷新提示。 */
export async function readNotificationEvents(
  response: Response,
  signal: AbortSignal,
  changed: (event?: string) => void,
) {
  if (!response.body || !response.headers.get('content-type')?.includes('text/event-stream'))
    throw new Error('消息流不可用')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = '',
    event = ''
  try {
    while (!signal.aborted) {
      const chunk = await reader.read()
      if (chunk.done) break
      buffer += decoder.decode(chunk.value, { stream: true })
      if (buffer.length > 65536) throw new Error('消息流格式异常')
      let end: number
      while ((end = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, end).replace(/\r$/, '')
        buffer = buffer.slice(end + 1)
        if (!line) {
          if (event === 'notifications-changed' || event === 'sync') changed(event)
          event = ''
        } else if (line.startsWith('event:')) event = line.slice(6).trim()
      }
    }
  } finally {
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}

/** 全站仅挂载一次；换号和退出中止连接，旧响应不能覆盖新账号状态。 */
export function useRealtimeUnread() {
  const auth = useLoginStore()
  const stop = watch(
    () => [auth.authenticated ? auth.user?.id : null, authToken.value] as const,
    ([owner, token], _, cleanup) => {
      unread.value = false
      if (!owner || !token) return
      const controller = new AbortController()
      const signal = controller.signal
      let disposed = false,
        loading = false,
        dirty = false,
        attempt = 0
      let reconnect: ReturnType<typeof setTimeout> | undefined
      let debounce: ReturnType<typeof setTimeout> | undefined
      let stream: AbortController | undefined
      let watchdog: ReturnType<typeof setTimeout> | undefined
      const valid = () => !disposed && authToken.value === token && auth.user?.id === owner

      // 连接按账号隔离；失败关闭并等待客户端重连，以数据库查询恢复真实状态。
      async function refresh() {
        if (!valid()) return
        if (loading) {
          dirty = true
          return
        }
        loading = true
        try {
          const response = await httpClient.get('/api/me/notifications/unread', {
            signal,
            headers: { Authorization: `Bearer ${token}` },
          })
          const value = response.data?.data
          if (
            valid() &&
            value?.userId === String(owner) &&
            Number.isSafeInteger(value.unreadCount) &&
            value.unreadCount >= 0
          )
            unread.value = value.unreadCount > 0
        } catch {
          /* 临时失败保留已知状态，下一次连接或提示继续核对。 */
        } finally {
          loading = false
          if (dirty && valid()) {
            dirty = false
            schedule()
          }
        }
      }
      function schedule() {
        if (!valid() || debounce) return
        debounce = setTimeout(() => {
          debounce = undefined
          void refresh()
        }, 100)
      }
      function pulse() {
        clearTimeout(watchdog)
        watchdog = setTimeout(() => stream?.abort(), 45000)
        schedule()
      }
      async function connect() {
        if (!valid()) return
        stream = new AbortController()
        pulse()
        try {
          const response = await fetch('/api/me/notifications/events', {
            signal: stream.signal,
            cache: 'no-store',
            headers: { Accept: 'text/event-stream', Authorization: `Bearer ${token}` },
          })
          if (response.status === 401) {
            clearAccessToken(token ?? undefined)
            return
          }
          if (!response.ok) throw new Error('消息连接暂不可用')
          // 连接按账号隔离；失败关闭并等待客户端重连，以数据库查询恢复真实状态。
          if (valid()) revision.value++
          schedule()
          await readNotificationEvents(response, stream.signal, (event) => {
            if (!valid()) return
            if (event === 'notifications-changed') revision.value++
            attempt = 0
            pulse()
          })
        } catch {
          /* 临时失败保留已知状态，下一次连接或提示继续核对。 */
        } finally {
          clearTimeout(watchdog)
          if (valid())
            reconnect = setTimeout(
              () => void connect(),
              Math.min(30000, 1000 * 2 ** Math.min(attempt++, 5)) + Math.random() * 500,
            )
        }
      }
      function resume() {
        if (document.visibilityState === 'visible') schedule()
      }
      window.addEventListener('online', resume)
      document.addEventListener('visibilitychange', resume)
      void refresh()
      void connect()
      cleanup(() => {
        disposed = true
        controller.abort()
        stream?.abort()
        clearTimeout(reconnect)
        clearTimeout(debounce)
        clearTimeout(watchdog)
        window.removeEventListener('online', resume)
        document.removeEventListener('visibilitychange', resume)
      })
    },
    { immediate: true, flush: 'sync' },
  )
  onScopeDispose(() => {
    stop()
    unread.value = false
  })
}
