<script setup lang="ts">
import { ref } from 'vue'

defineOptions({ inheritAttrs: false })
const model = defineModel<string>({ default: '' })
defineProps<{ disabled?: boolean }>()
const visible = ref(false)
// 显示开关仅作用于当前输入框，不提交表单，也不改变密码内容。
</script>

<template>
  <div class="password-input">
    <input
      v-bind="$attrs"
      v-model="model"
      :type="visible ? 'text' : 'password'"
      :disabled="disabled"
    />
    <button
      type="button"
      class="visibility-toggle"
      :aria-label="visible ? '隐藏密码' : '显示密码'"
      :aria-pressed="visible"
      :disabled="disabled"
      @click="visible = !visible"
    >
      <svg
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.6"
        aria-hidden="true"
      >
        <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z" />
        <circle cx="12" cy="12" r="3" />
        <path v-if="!visible" d="m3 3 18 18" />
      </svg>
    </button>
  </div>
</template>

<style scoped>
.password-input {
  position: relative;
  min-width: 0;
}
input {
  width: 100%;
  height: 50px;
  padding: 0 48px 0 15px;
  border: 1px solid #d8d0c2;
  border-radius: 14px;
  color: #2b2a26;
  background: #fff;
  font: inherit;
}
input:focus {
  outline: 2px solid #b67c00;
  outline-offset: 2px;
}
input:disabled {
  background: #f3eee5;
}
.visibility-toggle {
  position: absolute;
  top: 3px;
  right: 3px;
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  padding: 0;
  margin: 0;
  border: 0;
  border-radius: 10px;
  color: #80786d;
  background: transparent;
  cursor: pointer;
}
.visibility-toggle:hover {
  color: #b67c00;
}
.visibility-toggle:focus-visible {
  outline: 2px solid #b67c00;
}
svg {
  width: 20px;
  height: 20px;
}
</style>
