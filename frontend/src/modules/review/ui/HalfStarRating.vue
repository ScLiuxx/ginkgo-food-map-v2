<script setup lang="ts">
/** 展示为五星半星，仍以旧接口的十分制保存；键盘方向键每次调整半星。 */
defineProps<{ modelValue: number; label: string; id: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: number] }>()
/** 按每颗星的左右半区命中，不依赖浏览器滑块拇指宽度，触屏与鼠标口径一致。 */
function choose(event: globalThis.PointerEvent) {
  const input = event.currentTarget as globalThis.HTMLInputElement
  if (input.matches(':disabled')) return
  const bounds = input.getBoundingClientRect()
  const ratio = Math.max(0, Math.min(1, (event.clientX - bounds.left) / bounds.width))
  emit('update:modelValue', Math.ceil(ratio * 10))
  input.focus({ preventScroll: true })
  input.setPointerCapture?.(event.pointerId)
}
</script>

<template>
  <div class="star-control">
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
      @input="emit('update:modelValue', Number(($event.target as HTMLInputElement).value) * 2)"
    />
  </div>
</template>

<style scoped>
.star-control {
  position: relative;
  width: 172px;
  height: 36px;
  flex-shrink: 0;
}
.star-track {
  position: absolute;
  top: 0;
  left: 0;
  font-size: 30px;
  line-height: 36px;
  width: 100%;
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  white-space: nowrap;
  color: #e6e7e9;
}
.star {
  position: relative;
  width: 30px;
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
.star-control:focus-within {
  outline: 2px solid #f68b27;
  outline-offset: 3px;
  border-radius: 4px;
}
input:disabled {
  cursor: default;
}
</style>
