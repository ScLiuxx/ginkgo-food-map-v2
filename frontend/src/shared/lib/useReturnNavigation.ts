import { computed, toValue, type MaybeRefOrGetter } from 'vue'
import { useRoute, useRouter } from 'vue-router'

/** 仅将可浏览的站内页面作为来路，避免返回登录页、写入表单或站外地址。 */
export function isReturnDestination(value: unknown): value is string {
  // eslint-disable-next-line no-control-regex -- 明确拒绝控制字符，防止浏览器将异常地址解释为其他目标。
  if (typeof value !== 'string' || /[\\\u0000-\u0020\u007f]/.test(value)) return false
  const path = value.split(/[?#]/, 1)[0]
  return /^\/(?:account(?:\/(?:reviews|favorites|contributions|settings|growth|following|followers))?|users\/[1-9]\d*(?:\/(?:following|followers))?|restaurants\/[1-9]\d*|messages(?:\/(?:likes|comments|followers|system))?|restaurant-rankings|map)?$/.test(
    path ?? '',
  )
}

/** 普通点击回退原历史条目以恢复阅读现场；直达和修饰键打开仍有固定站内链接。 */
export function useReturnNavigation(fallback: MaybeRefOrGetter<string>) {
  const route = useRoute(),
    router = useRouter()
  const returnTarget = computed(() => {
    // 历史状态不是响应式数据，当前地址变化时重新核对来路。
    void route.fullPath
    const previous = router.options.history.state.back
    return isReturnDestination(previous) && previous.split(/[?#]/, 1)[0] !== route.path
      ? previous
      : toValue(fallback)
  })
  function returnToPrevious(event: MouseEvent) {
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey)
      return
    event.preventDefault()
    if (router.options.history.state.back === returnTarget.value) router.back()
    else void router.replace(returnTarget.value)
  }
  return { returnTarget, returnToPrevious }
}
