import { onBeforeUnmount, ref, watch } from 'vue'
import { useLoginStore } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'
import { notificationRevision } from './useRealtimeUnread'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import {
  fetchNotifications,
  markNotificationRead,
  markAllNotificationsRead,
  deleteNotification,
  type NotificationCategory,
  type NotificationItem,
  type NotificationPage,
} from '../api/notificationsApi'

/** 收件箱快照仅属于当前身份和分类；退出、换号、切页后取消请求并拒绝迟到回执。 */
export function useNotificationInbox(category: () => string | undefined) {
  const auth = useLoginStore()
  const result = ref<NotificationPage | null>(null),
    loading = ref(false),
    error = ref(''),
    actionError = ref(''),
    notice = ref(''),
    busy = ref(false)
  let context = 0,
    version = 0,
    requestedPage = 1
  let readAbort: AbortController | undefined, writeAbort: AbortController | undefined
  const scope = (): NotificationCategory =>
    category() === 'system'
      ? 'SYSTEM'
      : category() === 'comments'
        ? 'COMMENTS'
        : category() === 'likes'
          ? 'LIKES'
          : category() === 'followers'
            ? 'FOLLOWERS'
            : 'ALL'
  function identity() {
    return auth.authenticated &&
      auth.user &&
      authToken.value &&
      (!category() ||
        category() === 'system' ||
        category() === 'comments' ||
        category() === 'likes' ||
        category() === 'followers')
      ? { userId: String(auth.user.id), token: authToken.value }
      : null
  }
  async function load(page = requestedPage) {
    const actor = identity()
    if (!actor) return
    const ticket = ++version,
      owner = context
    readAbort?.abort()
    readAbort = new AbortController()
    requestedPage = page
    result.value = null
    error.value = ''
    loading.value = true
    try {
      const data = await fetchNotifications(
        scope(),
        page,
        actor.userId,
        actor.token,
        readAbort.signal,
      )
      if (owner === context && ticket === version) result.value = data
    } catch (reason) {
      if (owner === context && ticket === version) error.value = toErrorMessage(reason)
    } finally {
      if (owner === context && ticket === version) loading.value = false
    }
  }
  /** 一次只提交一项已读操作；成功后重读服务端快照，失败保留消息，不执行旧身份的跳转。 */
  async function read(item?: NotificationItem): Promise<NotificationItem | null> {
    const actor = identity()
    if (!actor || busy.value || loading.value || !result.value) return null
    const owner = context
    busy.value = true
    actionError.value = ''
    notice.value = ''
    writeAbort = new AbortController()
    try {
      if (item) await markNotificationRead(item.id, actor.token, writeAbort.signal)
      else await markAllNotificationsRead(scope(), actor.token, writeAbort.signal)
      if (owner !== context) return null
      await load()
      return owner === context && item ? item : null
    } catch (reason) {
      if (owner === context) actionError.value = toErrorMessage(reason)
      return null
    } finally {
      if (owner === context) busy.value = false
    }
  }
  /** 删除后从第一页重读服务端计数；GET失败与DELETE成功分别呈现，不重复删除。 */
  async function remove(item: NotificationItem): Promise<boolean> {
    const actor = identity()
    if (
      !actor ||
      busy.value ||
      loading.value ||
      !result.value?.records.some((row) => row.id === item.id)
    )
      return false
    const owner = context
    busy.value = true
    actionError.value = notice.value = ''
    writeAbort = new AbortController()
    try {
      await deleteNotification(item.id, actor.token, writeAbort.signal)
      if (owner !== context) return false
      notice.value = '消息已删除。'
      await load(1)
      return owner === context
    } catch (reason) {
      if (owner === context)
        actionError.value = `${toErrorMessage(reason)}。删除未确认，可刷新核对或重试。`
      return false
    } finally {
      if (owner === context) busy.value = false
    }
  }
  function clear() {
    context++
    version++
    readAbort?.abort()
    writeAbort?.abort()
    result.value = null
    loading.value = false
    busy.value = false
    error.value = ''
    actionError.value = ''
    notice.value = ''
    requestedPage = 1
  }
  watch(
    () => [auth.authenticated, auth.user?.id, authToken.value, category()],
    () => {
      clear()
      if (identity()) void load(1)
    },
    { immediate: true, flush: 'sync' },
  )
  // 合并同一轮提示，写操作结束后再读取，避免SSE打断本人的已读/删除回执。
  let refreshTimer: ReturnType<typeof setTimeout> | undefined
  let refreshPending = false
  watch(notificationRevision, () => {
    refreshPending = true
    clearTimeout(refreshTimer)
    refreshTimer = setTimeout(() => {
      if (!busy.value && !loading.value && identity()) {
        refreshPending = false
        void load()
      }
    }, 150)
  })
  watch([busy, loading], () => {
    if (refreshPending && !busy.value && !loading.value && identity()) {
      refreshPending = false
      clearTimeout(refreshTimer)
      refreshTimer = setTimeout(() => void load(), 150)
    }
  })
  onBeforeUnmount(() => {
    clearTimeout(refreshTimer)
    clear()
  })
  return { result, loading, error, actionError, notice, busy, load, read, remove }
}
