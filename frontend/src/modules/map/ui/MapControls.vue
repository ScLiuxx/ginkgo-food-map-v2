<script setup lang="ts">
import { useId } from 'vue'

defineProps<{ locating: boolean; message: string }>()
const emit = defineEmits<{ locate: [] }>()
const noticeId = useId()
</script>

<template>
  <div class="amap-controls">
    <slot />
    <button
      type="button"
      class="reset locate"
      :disabled="locating"
      :aria-describedby="noticeId"
      :aria-label="locating ? '定位中…' : '我的位置'"
      @click="emit('locate')"
    >
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <circle cx="12" cy="12" r="7" stroke="currentColor" stroke-width="1.6" />
        <circle cx="12" cy="12" r="2.5" fill="currentColor" />
        <path d="M12 1v4m0 14v4M1 12h4m14 0h4" stroke="currentColor" stroke-width="1.6" />
      </svg>
      <small>{{ locating ? '定位中…' : '我的位置' }}</small>
    </button>
  </div>
  <details class="location-notice">
    <summary>定位与隐私</summary>
    <p :id="noticeId">点击后申请一次定位，位置交由高德转换坐标；本站不绑定账号保存。</p>
  </details>
  <p v-if="message" class="location-status" role="status" aria-live="polite">
    {{ message }}
  </p>
</template>

<style>
.amap-controls {
  position: absolute;
  right: 16px;
  top: 16px;
  display: grid;
  gap: 0;
  z-index: 2;
  border-radius: 7px;
  overflow: hidden;
  box-shadow: 0 1px 6px #1e2c2c1c;
}
.amap-controls button {
  border: 0;
  min-width: 42px;
  min-height: 42px;
  background: white;
  font-size: 21px;
  cursor: pointer;
  color: #485353;
}
.amap-controls button:hover:enabled {
  background: #f4f6f6;
}
.amap-controls button:focus-visible,
.amap-controls ~ .location-notice summary:focus-visible {
  outline: 2px solid #9b713b;
  outline-offset: -3px;
}
.amap-controls .reset {
  display: grid;
  font-size: 20px;
  padding: 8px 5px;
  min-height: 52px;
  border-top: 1px solid #eff1f1;
}
.amap-controls .reset small {
  font-size: 10px;
}
.amap-controls .locate {
  justify-items: center;
  align-content: center;
  gap: 3px;
  color: #2876d9;
}
.amap-controls .locate:disabled {
  cursor: wait;
  opacity: 0.6;
}
.amap-controls ~ .location-notice,
.amap-controls ~ .location-status {
  position: absolute;
  top: 16px;
  right: 70px;
  z-index: 2;
  max-width: min(230px, calc(100% - 90px));
  margin: 0;
  padding: 7px 10px;
  border-radius: 6px;
  background: #fffffff0;
  color: #606a6a;
  font-size: 11px;
  line-height: 1.6;
}
.amap-controls ~ .location-notice summary {
  cursor: pointer;
  font-size: 10px;
}
.amap-controls ~ .location-notice[open] {
  z-index: 5;
  box-shadow: 0 2px 12px #25353512;
}
.amap-controls ~ .location-notice[open] summary {
  margin-bottom: 8px;
}
.amap-controls ~ .location-status {
  top: 56px;
  pointer-events: none;
}
.amap-controls ~ .location-notice p {
  margin: 0;
}
/* 固定的装饰光晕只突出蓝点，不代表按米绘制的定位精度范围。 */
.amap-my-location {
  display: block;
  width: 18px;
  height: 18px;
  border: 3px solid white;
  border-radius: 50%;
  background: #2876ed;
  box-shadow:
    0 0 0 7px #2876ed30,
    0 2px 6px #1237;
}
@media (max-width: 700px) {
  .amap-controls .locate {
    min-width: 40px;
    min-height: 40px;
    border: 0;
    padding: 6px;
  }
  .amap-controls .locate small {
    display: none;
  }
}
</style>
