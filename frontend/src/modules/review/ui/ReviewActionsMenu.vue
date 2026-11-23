<script setup lang="ts">
import { onMounted, onUnmounted, ref, useId } from 'vue'

/** 点评卡片的次要操作折叠入口；外点、移出焦点和Esc关闭，模态操作结束可回到可见按钮。 */
const root = ref<globalThis.HTMLDivElement>()
const trigger = ref<globalThis.HTMLButtonElement>()
const opened = ref(false)
const panelId = 'review-actions-' + useId()
function close() {
  opened.value = false
}
function restoreFocus() {
  close()
  trigger.value?.focus({ preventScroll: true })
}
function outside(event: globalThis.PointerEvent) {
  if (event.target instanceof globalThis.Node && !root.value?.contains(event.target)) close()
}
function leave(event: globalThis.FocusEvent) {
  if (event.relatedTarget instanceof globalThis.Node && !root.value?.contains(event.relatedTarget))
    close()
}
function select(event: globalThis.MouseEvent) {
  // 先由具体按钮执行原操作，再收起面板；模态关闭时由调用者恢复入口焦点。
  if (event.target instanceof globalThis.Element && event.target.closest('button, a')) close()
}
onMounted(() => globalThis.document.addEventListener('pointerdown', outside))
onUnmounted(() => globalThis.document.removeEventListener('pointerdown', outside))
</script>

<template>
  <div
    ref="root"
    class="review-actions-menu"
    @focusout="leave"
    @keydown.esc.stop.prevent="restoreFocus"
  >
    <button
      ref="trigger"
      type="button"
      class="more-review-actions"
      aria-label="更多操作"
      title="更多操作"
      :aria-expanded="opened"
      :aria-controls="panelId"
      @click="opened = !opened"
    >
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <circle cx="5" cy="12" r="1" />
        <circle cx="12" cy="12" r="1" />
        <circle cx="19" cy="12" r="1" />
      </svg>
    </button>
    <div
      v-show="opened"
      :id="panelId"
      class="review-actions-panel"
      role="group"
      aria-label="点评操作"
      @click="select"
    >
      <slot :restore-focus="restoreFocus" />
    </div>
  </div>
</template>

<style scoped>
.review-actions-menu {
  position: relative;
  margin-left: auto;
}
.more-review-actions {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border: 0;
  padding: 0;
  background: transparent;
  color: #8a95a3;
  border-radius: 6px;
  cursor: pointer;
}
.more-review-actions svg {
  width: 20px;
  height: 20px;
  fill: currentColor;
}
.review-actions-panel {
  position: absolute;
  bottom: calc(100% - 2px);
  right: 0;
  z-index: 5;
  min-width: 135px;
  border: 1px solid #e5e8ed;
  border-radius: 9px;
  padding: 5px;
  background: white;
  box-shadow: 0 5px 24px #2431421a;
}
.review-actions-menu .review-actions-panel :deep(button) {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  min-height: 44px;
  padding: 8px 12px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: #697585;
  font: inherit;
  font-size: 12px;
  text-align: left;
  white-space: nowrap;
  cursor: pointer;
}
.review-actions-menu .review-actions-panel :deep(button.delete-review) {
  color: #ac6856;
}
.review-actions-panel :deep(button:hover:not(:disabled)) {
  background: #f6f7f9;
}
.review-actions-panel :deep(button:disabled) {
  opacity: 0.5;
  cursor: default;
}
.more-review-actions:focus-visible,
.review-actions-panel :deep(button:focus-visible) {
  outline: 2px solid #cf9f53;
  outline-offset: 2px;
}
</style>
