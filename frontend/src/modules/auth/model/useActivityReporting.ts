import { onBeforeUnmount, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useLoginStore } from './login.store'
import { authToken } from '@/shared/api/authSession'
import { httpClient } from '@/shared/api/httpClient'

/** 只由主动路由访问、登录完成及恢复可见触发，不设置心跳，不持久缓存当天成功状态。 */
export function useActivityReporting() {
  const auth = useLoginStore()
  const route = useRoute()
  let controller: AbortController | undefined
  let disposed = false
  function cancel() {
    controller?.abort()
    controller = undefined
  }
  async function report() {
    cancel()
    if (
      disposed ||
      document.visibilityState === 'hidden' ||
      !auth.authenticated ||
      auth.restoring ||
      auth.isAdministrator ||
      !auth.user?.roles.includes('ROLE_USER') ||
      !authToken.value ||
      !route.matched.length ||
      route.meta.layout === 'admin' ||
      route.path.startsWith('/previews/') ||
      ['/login', '/register', '/password-reset'].includes(route.path)
    )
      return
    controller = new AbortController()
    try {
      await httpClient.post('/api/me/activity', undefined, {
        headers: { Authorization: `Bearer ${authToken.value}` },
        signal: controller.signal,
      })
    } catch {
      // 统计是尽力上报，失败不打断浏览；下一次主动访问可再尝试，服务端按账号去重。
    }
  }
  function visibilityChanged() {
    if (document.visibilityState === 'hidden') cancel()
    else void report()
  }
  watch(
    [
      () => route.fullPath,
      () => route.matched.length,
      authToken,
      () => auth.user?.id,
      () => auth.user?.roles.join(','),
      () => auth.restoring,
      () => auth.authenticated,
    ],
    () => void report(),
    { immediate: true, flush: 'post' },
  )
  onMounted(() => document.addEventListener('visibilitychange', visibilityChanged))
  onBeforeUnmount(() => {
    disposed = true
    cancel()
    document.removeEventListener('visibilitychange', visibilityChanged)
  })
}
