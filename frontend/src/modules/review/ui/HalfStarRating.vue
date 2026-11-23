<script setup lang="ts">
import { ref } from 'vue'
/** 展示为五星半星，仍以旧接口的十分制保存；键盘方向键每次调整半星。 */
defineProps<{ modelValue: number; label: string; id: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: number] }>()
// range在部分浏览器鼠标点击后仍匹配focus-visible；显式区分指针输入，保留键盘焦点。
const pointerFocus = ref(false)
/** 按每颗星的左右半区命中，不依赖浏览器滑块拇指宽度，触屏与鼠标口径一致。 */
function choose(event: globalThis.PointerEvent) {
  const input = event.currentTarget as globalThis.HTMLInputElement
  if (input.matches(':disabled')) return
  pointerFocus.value = true
  const bounds = input.getBoundingClientRect()
  const ratio = Math.max(0, Math.min(1, (event.clientX - bounds.left) / bounds.width))
  emit('update:modelValue', Math.ceil(ratio * 10))
  input.focus({ preventScroll: true })
  input.setPointerCapture?.(event.pointerId)
}
</script>

<template>
  <div class="star-control" :class="{ 'pointer-focus': pointerFocus }">
    <span class="star-track" aria-hidden="true">
      <span v-for="star in 5" :key="star" class="star">
        ★<span
          class="star-fill"
          :style="{ width: `${Math.max(0, Math.min(1, modelValue / 2 - star + 1)) * 100}%` }"
          >★</span
        >
      </span>
    </span>
    <input
      :id="id"
      type="range"
      min="0"
      max="5"
      step="0.5"
      :value="modelValue / 2"
      :aria-label="label"
      :aria-valuetext="`${modelValue / 2} 星，满分 5 星`"
      @pointerdown.prevent="choose"
      @pointermove="$event.buttons === 1 && choose($event)"
      @keydown="pointerFocus = false"
      @blur="pointerFocus = false"
      @input="emit('update:modelValue', Number(($event.target as HTMLInputElement).value) * 2)"
    />
  </div>
</template>

<style scoped>
.star-control {
  position: relative;
  width: var(--rating-control-width, 172px);
  max-width: 100%;
  height: var(--rating-control-height, 36px);
  flex-shrink: 0;
}
.star-track {
  position: absolute;
  top: 0;
  left: 0;
  font-size: var(--rating-star-size, 30px);
  line-height: var(--rating-control-height, 36px);
  width: 100%;
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  white-space: nowrap;
  color: #e6e7e9;
}
.star {
  position: relative;
  width: var(--rating-star-size, 30px);
  justify-self: center;
}
.star-fill {
  position: absolute;
  inset: 0 auto 0 0;
  overflow: hidden;
  color: #f68b27;
}
input {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  margin: 0;
  opacity: 0;
  cursor: pointer;
  touch-action: pan-y;
}
.star-control:not(.pointer-focus):has(input:focus-visible) {
  outline: 2px solid #a2a8af;
  outline-offset: 3px;
  border-radius: 4px;
}
input:disabled {
  cursor: default;
}
</style>
