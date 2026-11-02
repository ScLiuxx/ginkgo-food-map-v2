import { computed, onScopeDispose, ref } from 'vue'

/** 临时悬停与点击固定分开；短暂延迟允许鼠标跨越点位和卡片间隙，离开后不残留。 */
export function useMapSelection(initialId: string | null = null) {
  const pinnedId = ref<string | null>(initialId)
  const hoveredId = ref<string | null>(null)
  let timer: ReturnType<typeof setTimeout> | undefined
  function keepOpen() {
    clearTimeout(timer)
  }
  function hover(id: string) {
    keepOpen()
    hoveredId.value = id
  }
  function leave() {
    keepOpen()
    timer = setTimeout(() => {
      hoveredId.value = null
    }, 280)
  }
  function pin(id: string) {
    keepOpen()
    pinnedId.value = id
    hoveredId.value = null
  }
  function close() {
    keepOpen()
    pinnedId.value = null
    hoveredId.value = null
  }
  onScopeDispose(keepOpen)
  return {
    activeId: computed(() => hoveredId.value ?? pinnedId.value),
    pinnedId,
    hoveredId,
    hover,
    leave,
    pin,
    close,
    keepOpen,
  }
}
