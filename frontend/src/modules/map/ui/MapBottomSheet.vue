<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'

type Snap = 'collapsed' | 'half' | 'full'
/** 手机列表与选店卡片共用面板；地图仍挂载，面板高度由实际剩余视口决定。 */
const props = defineProps<{
  listId: string
  selectionId: string
  selected: boolean
  selectionHeight: number
  title: string
}>()
const snap = defineModel<Snap>({ default: 'collapsed' })
const emit = defineEmits<{ resize: [height: number] }>()
const region = ref<globalThis.HTMLElement>()
const panel = ref<globalThis.HTMLElement>()
const available = ref(400)
const dragging = ref(false)
const dragHeight = ref(0)
const stops = computed(() => {
  // 全展开由列表覆盖整个地图区；底图仍保持尺寸和会话，不留下被压扁的地图条。
  const full = Math.max(100, available.value)
  const collapsed = Math.min(props.selected ? props.selectionHeight + 30 : 148, full * 0.65)
  return { collapsed, half: Math.min(full, Math.max(collapsed + 40, full * 0.65)), full }
})
const height = computed(() => (dragging.value ? dragHeight.value : stops.value[snap.value]))
const showSelection = computed(() => props.selected && snap.value === 'collapsed')
watch(height, (value) => emit('resize', Math.round(value)), { immediate: true })
watch(snap, (value) => {
  const list = panel.value?.querySelector<globalThis.HTMLElement>('.map-result-panel')
  if (value !== 'full' && list) list.scrollTop = 0
})
let observer: globalThis.ResizeObserver | undefined
let gesture:
  | {
      id: number
      x: number
      y: number
      height: number
      time: number
      handle: boolean
      canPull: boolean
      nativeScroll: boolean
    }
  | undefined
let suppressClickUntil = 0
const order: Snap[] = ['collapsed', 'half', 'full']

/** 全屏列表保留原生惯性滚动；仅在手势起始已到顶部且向下拖时接管。 */
function begin(id: number, x: number, y: number, target: globalThis.EventTarget | null) {
  if (gesture || !(target instanceof globalThis.Element)) return
  if (target.closest('a, input, select, textarea, label, .map-selection-actions, .map-popup-close'))
    return
  const scroller = panel.value?.querySelector<globalThis.HTMLElement>(
    showSelection.value ? '.mobile-sheet-selection' : '.map-result-panel',
  )
  gesture = {
    id,
    x,
    y,
    height: height.value,
    time: globalThis.performance.now(),
    handle: !!target.closest('.mobile-sheet-grip'),
    canPull: !scroller || scroller.scrollTop <= 0,
    // 极短屏幕或很长的店名需要在选店卡片内滚动，不能上滑就把导航按钮切走。
    nativeScroll:
      snap.value === 'full' ||
      Boolean(showSelection.value && scroller && scroller.scrollHeight > scroller.clientHeight),
  }
}
function move(x: number, y: number, event: globalThis.Event) {
  if (!gesture) return
  const dy = y - gesture.y
  if (!dragging.value) {
    if (Math.abs(x - gesture.x) > Math.abs(dy) && Math.abs(x - gesture.x) > 8) {
      gesture = undefined
      return
    }
    if (Math.abs(dy) < 8) return
    if (gesture.nativeScroll && !gesture.handle && (!gesture.canPull || dy < 0)) {
      gesture = undefined
      return
    }
    dragging.value = true
  }
  if (event.cancelable) event.preventDefault()
  dragHeight.value = Math.max(
    stops.value.collapsed,
    Math.min(stops.value.full, gesture.height - dy),
  )
}
function finish(y: number, cancelled = false) {
  if (!gesture) return
  if (dragging.value) {
    suppressClickUntil = globalThis.performance.now() + 350
    if (!cancelled) {
      const dy = y - gesture.y
      const speed = Math.abs(dy) / Math.max(1, globalThis.performance.now() - gesture.time)
      let nearest = order.reduce((best, value) =>
        Math.abs(stops.value[value] - dragHeight.value) <
        Math.abs(stops.value[best] - dragHeight.value)
          ? value
          : best,
      )
      // 短促滑动也可进入相邻停靠位，拖得较远时以最近的位置为准。
      if (Math.abs(dy) > 30 && (speed > 0.45 || nearest === snap.value)) {
        const next = Math.max(0, Math.min(2, order.indexOf(snap.value) + (dy < 0 ? 1 : -1)))
        nearest =
          order[
            dy < 0 ? Math.max(next, order.indexOf(nearest)) : Math.min(next, order.indexOf(nearest))
          ]!
      }
      snap.value = nearest
    }
  }
  gesture = undefined
  dragging.value = false
}
function touchStart(event: globalThis.TouchEvent) {
  if (event.touches.length !== 1) {
    finish(0, true)
    return
  }
  const touch = event.touches[0]!
  begin(touch.identifier, touch.clientX, touch.clientY, event.target)
}
function touchMove(event: globalThis.TouchEvent) {
  if (event.touches.length !== 1) {
    finish(0, true)
    return
  }
  const touch = [...event.touches].find((t) => t.identifier === gesture?.id)
  if (touch) move(touch.clientX, touch.clientY, event)
}
function touchEnd(event: globalThis.TouchEvent) {
  const touch = [...event.changedTouches].find((t) => t.identifier === gesture?.id)
  if (touch) finish(touch.clientY)
}
function pointerDown(event: globalThis.PointerEvent) {
  // 触屏由非 passive 的 touchmove 决定滚动归属；鼠标使用捕获保证拖出面板后仍能释放。
  if (event.pointerType !== 'mouse' || event.button !== 0) return
  begin(event.pointerId, event.clientX, event.clientY, event.target)
}
function pointerMove(event: globalThis.PointerEvent) {
  if (event.pointerType === 'mouse' && event.pointerId === gesture?.id) {
    move(event.clientX, event.clientY, event)
    if (dragging.value) panel.value?.setPointerCapture?.(event.pointerId)
  }
}
function pointerEnd(event: globalThis.PointerEvent) {
  if (event.pointerType === 'mouse' && event.pointerId === gesture?.id)
    finish(event.clientY, event.type !== 'pointerup')
}
function preventDragClick(event: globalThis.MouseEvent) {
  if (globalThis.performance.now() >= suppressClickUntil) return
  event.preventDefault()
  event.stopPropagation()
}
/** 手柄提供等价的键盘操作；界面只展示短横线，不增加文字切换入口。 */
function keydown(event: globalThis.KeyboardEvent) {
  if (!['ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  snap.value =
    event.key === 'Home'
      ? 'collapsed'
      : event.key === 'End'
        ? 'full'
        : order[
            Math.max(0, Math.min(2, order.indexOf(snap.value) + (event.key === 'ArrowUp' ? 1 : -1)))
          ]!
}
function focusList(event: globalThis.FocusEvent) {
  if (event.target instanceof globalThis.Element && event.target.matches(':focus-visible'))
    snap.value = 'full'
}
onMounted(() => {
  if (!region.value) return
  const measure = () => {
    const value = region.value?.getBoundingClientRect().height
    if (value) {
      finish(0, true)
      available.value = value
    }
  }
  measure()
  if (globalThis.ResizeObserver) {
    observer = new globalThis.ResizeObserver(measure)
    observer.observe(region.value)
  }
})
onUnmounted(() => observer?.disconnect())
</script>

<template>
  <div ref="region" class="map-sheet-region">
    <section
      ref="panel"
      class="mobile-map-sheet"
      :class="{ dragging, 'showing-selection': showSelection }"
      :data-snap="snap"
      :style="{ height: `${height}px` }"
      aria-label="餐厅面板"
      @touchstart="touchStart"
      @touchmove="touchMove"
      @touchend="touchEnd"
      @touchcancel="finish(0, true)"
      @pointerdown="pointerDown"
      @pointermove="pointerMove"
      @pointerup="pointerEnd"
      @pointercancel="pointerEnd"
      @lostpointercapture="pointerEnd"
      @click.capture="preventDragClick"
      @keydown.esc="snap = 'collapsed'"
    >
      <button
        type="button"
        class="mobile-sheet-grip"
        :aria-label="snap === 'full' ? '收起餐厅面板' : '展开餐厅面板'"
        :aria-expanded="snap !== 'collapsed'"
        :aria-controls="listId"
        @click="snap = snap === 'full' ? 'collapsed' : order[order.indexOf(snap) + 1]!"
        @keydown="keydown"
      >
        <span aria-hidden="true"></span>
      </button>
      <div :id="selectionId" :hidden="!showSelection" class="mobile-sheet-selection"></div>
      <div
        :id="listId"
        :hidden="showSelection"
        class="mobile-sheet-results"
        @focusin="focusList"
      ></div>
      <span class="sheet-screen-reader">{{ title }}，可拖动面板浏览餐厅</span>
    </section>
  </div>
</template>

<style scoped>
.map-sheet-region {
  grid-column: 1;
  grid-row: 3;
  position: relative;
  min-height: 0;
  z-index: 6;
  pointer-events: none;
}
.mobile-map-sheet {
  position: absolute;
  inset: auto 0 0;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  border-radius: 18px 18px 0 0;
  background: white;
  box-shadow: 0 -4px 18px #22302312;
  pointer-events: auto;
  overscroll-behavior: contain;
}
.mobile-map-sheet.dragging {
  user-select: none;
}
.mobile-sheet-grip {
  display: flex;
  flex: 0 0 28px;
  width: 100%;
  align-items: center;
  justify-content: center;
  border: 0;
  padding: 0;
  background: white;
  cursor: grab;
  touch-action: none;
}
.mobile-sheet-grip span {
  width: 34px;
  height: 4px;
  border-radius: 4px;
  background: #d6dcd5;
}
.mobile-sheet-grip:focus-visible {
  outline: 2px solid #a97326;
  outline-offset: -4px;
}
.mobile-sheet-results,
.mobile-sheet-selection {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.mobile-sheet-selection {
  overflow: auto;
  overscroll-behavior: contain;
}
[hidden] {
  display: none;
}
.sheet-screen-reader {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
}
</style>
