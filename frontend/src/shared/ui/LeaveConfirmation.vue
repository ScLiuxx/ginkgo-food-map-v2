<script setup lang="ts">
import { onBeforeUnmount, useId, useTemplateRef } from 'vue'

defineProps<{ message: string }>()
const dialog = useTemplateRef('dialog')
const titleId = useId()
let resolvePending: ((leave: boolean) => void) | undefined

function finish(leave: boolean) {
  const resolve = resolvePending
  resolvePending = undefined
  dialog.value?.close()
  resolve?.(leave)
}

/** 路由守卫等待用户选择；重复导航先取消上一次等待，卸载时也不能留下悬空导航。 */
function ask(): Promise<boolean> {
  if (!dialog.value) return Promise.resolve(false)
  if (resolvePending) finish(false)
  return new Promise((resolve) => {
    resolvePending = resolve
    dialog.value?.showModal()
  })
}

onBeforeUnmount(() => finish(false))
defineExpose({ ask })
</script>

<template>
  <Teleport to="body">
    <dialog
      ref="dialog"
      class="leave-confirmation"
      :aria-labelledby="titleId"
      @cancel.prevent="finish(false)"
      @close="!dialog?.open && resolvePending && finish(false)"
    >
      <h2 :id="titleId">{{ message }}</h2>
      <footer>
        <button type="button" autofocus @click="finish(false)">继续编辑</button>
        <button type="button" class="leave" @click="finish(true)">离开</button>
      </footer>
    </dialog>
  </Teleport>
</template>

<style scoped>
.leave-confirmation {
  box-sizing: border-box;
  width: min(360px, calc(100vw - 32px));
  max-height: calc(100vh - 32px);
  max-height: calc(100dvh - 32px);
  padding: 24px;
  overflow: auto;
  border: 0;
  border-radius: 16px;
  background: #fff;
  color: #30343b;
  box-shadow: 0 16px 56px #00000026;
}
.leave-confirmation::backdrop {
  background: #00000059;
}
h2 {
  margin: 0 0 24px;
  font-size: 17px;
  font-weight: 600;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
button {
  min-height: 40px;
  padding: 9px 16px;
  border: 1px solid #e0e3e7;
  border-radius: 8px;
  background: #fff;
  color: #59616b;
  font: inherit;
  font-size: 14px;
  cursor: pointer;
}
button:hover {
  background: #f5f6f7;
}
button.leave {
  border-color: #f5820a;
  background: #f5820a;
  color: #fff;
}
button.leave:hover {
  border-color: #dc7205;
  background: #dc7205;
}
button:focus-visible {
  outline: 2px solid #8c939c;
  outline-offset: 3px;
}
</style>
