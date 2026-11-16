<script setup lang="ts">
import { computed, ref } from 'vue'
import type { SubmissionPhotos } from '../model/useSubmissionPhotos'

/** 表单共用上传外观，具体文件用途与生命周期由各自草稿控制。 */
const props = defineProps<{
  draft: SubmissionPhotos
  label: string
  hint: string
  disabled?: boolean
}>()
const input = ref<globalThis.HTMLInputElement>()
const photos = computed(() => props.draft.photos.value)
function choose(event: globalThis.Event) {
  const target = event.target as globalThis.HTMLInputElement
  if (!props.disabled) void props.draft.addFiles(target.files)
  target.value = ''
}
</script>
<template>
  <div class="submission-photos">
    <p class="photo-hint">{{ hint }}</p>
    <input
      ref="input"
      class="photo-input"
      type="file"
      accept="image/jpeg,image/png,image/webp"
      multiple
      :aria-label="`选择${label}`"
      :disabled="disabled || draft.processing.value"
      @change="choose"
    />
    <div class="photo-grid">
      <div v-for="(photo, index) in photos" :key="photo.url" class="photo-tile">
        <div class="photo-preview">
          <img :src="photo.url" :alt="`${label} ${index + 1}`" /><button
            type="button"
            class="remove"
            :aria-label="`移除${label}${index + 1}`"
            :disabled="disabled"
            @click="draft.remove(index)"
          >
            ×
          </button>
        </div>
        <small role="status">{{
          photo.state === 'uploading'
            ? '上传中…'
            : photo.state === 'uploaded'
              ? '已上传'
              : photo.error
        }}</small>
        <button
          v-if="photo.state === 'failed'"
          class="retry"
          type="button"
          :disabled="disabled"
          @click="draft.retry(photo)"
        >
          重试上传
        </button>
      </div>
      <button
        v-if="photos.length < 5"
        type="button"
        class="upload"
        :aria-label="`添加${label}`"
        :disabled="disabled || draft.processing.value"
        @click="input?.click()"
      >
        <span>＋</span>添加照片
      </button>
    </div>
    <small>最多5张，支持JPEG、PNG、WebP，自动压缩。</small>
    <p v-if="draft.processing.value" role="status" class="photo-hint">正在处理图片…</p>
    <p v-if="draft.error.value" role="alert" class="error">{{ draft.error.value }}</p>
  </div>
</template>
<style scoped>
.photo-input {
  display: none;
}
.photo-hint,
small {
  color: #818b93;
  font-size: 12px;
  line-height: 1.8;
}
.photo-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin: 12px 0;
}
.photo-tile {
  width: 100px;
  overflow-wrap: anywhere;
}
.photo-preview {
  height: 90px;
  position: relative;
}
.photo-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 7px;
}
button {
  font: inherit;
  cursor: pointer;
}
.remove {
  position: absolute;
  right: 4px;
  top: 4px;
  border: 0;
  border-radius: 50%;
  background: #30343bcc;
  color: white;
  width: 24px;
  height: 24px;
  font-size: 18px;
}
.upload {
  height: 90px;
  width: 100px;
  border: 1px dashed #d4d9d7;
  border-radius: 7px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 5px;
  background: #fcfdfb;
  color: #939b9f;
  font-size: 11px;
}
.upload span {
  font-size: 24px;
}
.retry {
  padding: 4px 7px;
  border: 1px solid #e3cda9;
  border-radius: 4px;
  color: #aa742e;
  background: #fff9ef;
  font-size: 11px;
}
.error {
  color: #b64e37;
  font-size: 12px;
}
button:disabled {
  opacity: 0.5;
  cursor: default;
}
button:focus-visible {
  outline: 2px solid #ed740a;
  outline-offset: 3px;
}
</style>
