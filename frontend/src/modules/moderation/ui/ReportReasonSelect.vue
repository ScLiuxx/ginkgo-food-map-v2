<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { reportReasons, type ReportReason } from '../model/reportReasons'

/** 原因仍只取固定枚举；自绘箭头准确跟随展开状态，并保留键盘选择和外点关闭。 */
const model = defineModel<ReportReason | ''>({ default: '' })
const props = defineProps<{ inputId: string; disabled: boolean }>()
const root = ref<globalThis.HTMLDivElement>()
const trigger = ref<globalThis.HTMLButtonElement>()
const opened = ref(false),
  active = ref(0)
const selected = computed(() => reportReasons.find((item) => item.value === model.value))
const listId = computed(() => props.inputId + '-options')
function close(restoreFocus = false) {
  opened.value = false
  if (restoreFocus) trigger.value?.focus({ preventScroll: true })
}
function show(last = false) {
  if (props.disabled) return
  const index = reportReasons.findIndex((item) => item.value === model.value)
  active.value = index >= 0 ? index : last ? reportReasons.length - 1 : 0
  opened.value = true
}
function choose(index: number) {
  if (props.disabled) return
  const item = reportReasons[index]
  if (!item) return
  model.value = item.value
  close(true)
}
function key(event: globalThis.KeyboardEvent) {
  if (props.disabled) return
  if (event.key === 'Escape' && opened.value) {
    event.preventDefault()
    event.stopPropagation()
    close(true)
  } else if (event.key === 'Tab') close()
  else if (['ArrowDown', 'ArrowUp', 'Home', 'End', 'Enter', ' '].includes(event.key)) {
    event.preventDefault()
    if (event.key === 'Enter' || event.key === ' ') {
      if (opened.value) choose(active.value)
      else show()
    } else if (!opened.value) show(event.key === 'ArrowUp' || event.key === 'End')
    else if (event.key === 'Home') active.value = 0
    else if (event.key === 'End') active.value = reportReasons.length - 1
    else
      active.value =
        (active.value + (event.key === 'ArrowDown' ? 1 : -1) + reportReasons.length) %
        reportReasons.length
    // 选项列表在窄屏可滚动，键盘移动时保持当前项可见，不移动页面。
    globalThis.requestAnimationFrame?.(() =>
      root.value?.querySelector('[data-active="true"]')?.scrollIntoView?.({ block: 'nearest' }),
    )
  }
}
function outside(event: globalThis.PointerEvent) {
  if (event.target instanceof globalThis.Node && !root.value?.contains(event.target)) close()
}
function leave(event: globalThis.FocusEvent) {
  if (event.relatedTarget instanceof globalThis.Node && !root.value?.contains(event.relatedTarget))
    close()
}
watch(
  () => props.disabled,
  (value) => {
    if (value) close()
  },
)
onMounted(() => globalThis.document.addEventListener('pointerdown', outside))
onUnmounted(() => globalThis.document.removeEventListener('pointerdown', outside))
</script>
<template>
  <div ref="root" class="report-reason-select" @focusout="leave">
    <button
      :id="inputId"
      ref="trigger"
      type="button"
      class="reason-trigger"
      role="combobox"
      aria-label="举报原因"
      aria-haspopup="listbox"
      :aria-expanded="opened"
      :aria-controls="listId"
      :aria-activedescendant="opened ? `${inputId}-option-${active}` : undefined"
      :disabled="disabled"
      @click="opened ? close() : show()"
      @keydown="key"
    >
      <span>{{ selected?.label ?? '请选择举报原因' }}</span>
      <svg :class="{ expanded: opened }" viewBox="0 0 24 24" aria-hidden="true">
        <path d="m7 10 5 5 5-5" />
      </svg>
    </button>
    <ul v-if="opened" :id="listId" class="reason-options" role="listbox" aria-label="举报原因选项">
      <li
        v-for="(item, index) in reportReasons"
        :id="`${inputId}-option-${index}`"
        :key="item.value"
        role="option"
        :aria-selected="model === item.value"
        :data-active="active === index"
        @pointermove="active = index"
        @mousedown.prevent
        @click="choose(index)"
      >
        {{ item.label }}<span v-if="model === item.value" aria-hidden="true">✓</span>
      </li>
    </ul>
  </div>
</template>
<style scoped>
.report-reason-select {
  position: relative;
}
.report-reason-select .reason-trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  min-height: 42px;
  box-sizing: border-box;
  border: 1px solid #e6e8eb;
  border-radius: 5px;
  padding: 10px 12px;
  background: #fafbfc;
  color: #38434e;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.report-reason-select .reason-trigger:focus {
  outline: none;
  box-shadow: none;
  border-color: #e6e8eb;
}
.reason-trigger svg {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
  transition: transform 0.15s;
}
.reason-trigger svg.expanded {
  transform: rotate(180deg);
}
.reason-trigger:disabled {
  opacity: 0.55;
  cursor: default;
}
.reason-options {
  position: absolute;
  top: calc(100% + 5px);
  left: 0;
  right: 0;
  z-index: 3;
  max-height: min(248px, 40vh);
  max-height: min(248px, 40dvh);
  overflow-y: auto;
  overscroll-behavior: contain;
  margin: 0;
  padding: 4px;
  list-style: none;
  border: 1px solid #e6e8eb;
  border-radius: 7px;
  background: #fff;
  box-shadow: 0 5px 20px #26334212;
}
.reason-options li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 36px;
  padding: 8px 9px;
  border-radius: 4px;
  color: #596571;
  font-size: 13px;
  cursor: pointer;
}
.reason-options li[data-active='true'] {
  background: #f0f2f5;
}
.reason-options li[aria-selected='true'] {
  color: #344252;
}
@media (prefers-reduced-motion: reduce) {
  .reason-trigger svg {
    transition: none;
  }
}
</style>
