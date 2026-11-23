import { onMounted, onUnmounted, ref } from 'vue'

/** 用同一断点控制需要移动DOM的布局，跨断点时保留原组件及其草稿。 */
export function useMediaQuery(query: string) {
  const media = globalThis.matchMedia?.(query)
  const matches = ref(media?.matches ?? false)
  const update = () => {
    matches.value = media?.matches ?? false
  }
  onMounted(() => media?.addEventListener('change', update))
  onUnmounted(() => media?.removeEventListener('change', update))
  return matches
}
