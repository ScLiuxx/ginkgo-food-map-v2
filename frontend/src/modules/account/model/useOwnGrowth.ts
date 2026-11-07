import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useLoginStore } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { fetchOwnGrowth, type GrowthSummary } from '../api/growthApi'

/** 成长数据仅存在当前页面内；退出、换号和卸载时取消请求并清除旧快照。 */
export function useOwnGrowth(enabled: () => boolean) {
  const auth = useLoginStore()
  const summary = ref<GrowthSummary | null>(null)
  const loading = ref(false)
  const error = ref('')
  let controller: AbortController | undefined
  let generation = 0

  async function refresh() {
    const ticket = ++generation
    controller?.abort()
    summary.value = null
    loading.value = false
    error.value = ''
    const token = authToken.value
    const userId = auth.user ? String(auth.user.id) : null
    if (!enabled() || !auth.authenticated || !token || !userId) return
    controller = new AbortController()
    loading.value = true
    try {
      const result = await fetchOwnGrowth(token, controller.signal)
      if (ticket !== generation) return
      if (result.userId !== userId) throw new Error('成长资料与当前账号不一致，请重新加载。')
      summary.value = result
    } catch (reason) {
      if (ticket === generation) error.value = toErrorMessage(reason)
    } finally {
      if (ticket === generation) loading.value = false
    }
  }

  watch(() => [enabled(), auth.authenticated, auth.user?.id, authToken.value], refresh, {
    immediate: true,
    flush: 'sync',
  })
  onBeforeUnmount(() => {
    generation++
    controller?.abort()
  })
  const progress = computed(() => {
    const item = summary.value
    if (!item) return 0
    if (item.nextLevelThreshold === null) return 100
    return Math.max(
      0,
      Math.min(
        100,
        ((item.experience - item.currentLevelThreshold) /
          (item.nextLevelThreshold - item.currentLevelThreshold)) *
          100,
      ),
    )
  })
  return { summary, loading, error, progress, refresh }
}
