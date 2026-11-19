import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { isAxiosError } from 'axios'
import { useLoginStore } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'
import { fetchActivityStatistics } from '../api/activityStatisticsApi'
import type { ActivityStatistics } from './activityStatistics'

/** 只在可见且管理员身份有效时读取，隐藏、卸载及换号使在途结果失效。 */
export function useAdminActivityStatistics() {
  const auth = useLoginStore()
  const data = ref<ActivityStatistics | null>(null)
  const loading = ref(false)
  const error = ref('')
  const unavailable = ref(false)
  let revision = 0,
    disposed = false
  let controller: AbortController | undefined
  let timer: ReturnType<typeof setTimeout> | undefined
  const visible = () => document.visibilityState !== 'hidden'
  function stop() {
    revision++
    controller?.abort()
    clearTimeout(timer)
    loading.value = false
  }
  async function load() {
    const token = authToken.value,
      userId = auth.user?.id
    if (disposed || !visible() || !token || !userId || !auth.isAdministrator || auth.restoring)
      return
    stop()
    const ticket = revision
    controller = new AbortController()
    loading.value = true
    const valid = () =>
      !disposed &&
      ticket === revision &&
      token === authToken.value &&
      userId === auth.user?.id &&
      auth.isAdministrator &&
      !auth.restoring &&
      visible()
    try {
      const result = await fetchActivityStatistics(token, controller.signal)
      if (valid()) {
        data.value = result
        error.value = ''
        unavailable.value = false
      }
    } catch (reason) {
      if (valid()) {
        const status = isAxiosError(reason) ? reason.response?.status : undefined
        unavailable.value = status === 404 || status === 501
        error.value = unavailable.value
          ? '使用统计暂未开放'
          : status === 403
            ? '当前账号没有统计查看权限'
            : status === 401
              ? '登录状态已失效，请重新登录'
              : '暂时无法读取统计数据'
      }
    } finally {
      if (valid()) {
        loading.value = false
        timer = setTimeout(() => void load(), 60_000)
      }
    }
  }
  function visibilityChanged() {
    if (visible()) void load()
    else stop()
  }
  watch(
    [authToken, () => auth.user?.id, () => auth.isAdministrator, () => auth.restoring],
    () => {
      stop()
      data.value = null
      error.value = ''
      unavailable.value = false
      void load()
    },
    { immediate: true, flush: 'sync' },
  )
  onMounted(() => document.addEventListener('visibilitychange', visibilityChanged))
  onBeforeUnmount(() => {
    disposed = true
    stop()
    document.removeEventListener('visibilitychange', visibilityChanged)
  })
  return { data, loading, error, unavailable, load }
}
