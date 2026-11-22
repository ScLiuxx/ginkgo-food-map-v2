<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { authToken, getAccessToken } from '@/shared/api/authSession'
import { uploadImage, cancelImage } from '@/modules/file/api/mediaApi'
import { compressPhoto } from '@/modules/file/model/photos'
import {
  fetchWindowPhotos,
  addWindowPhoto,
  removeWindowPhoto,
  type WindowPhoto,
} from '@/modules/restaurant/api/canteenWindowApi'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'

/** 相册操作立即保存；上传和入册分开，结果未知时保留原fileId供幂等重试。 */
const props = withDefaults(defineProps<{ id: string; name: string; disabled?: boolean }>(), {
  disabled: false,
})
const emit = defineEmits<{
  close: []
  changed: []
  busy: [value: boolean]
  pending: [value: boolean]
}>()
const token = getAccessToken()
const photos = ref<WindowPhoto[]>([])
const busy = ref(false),
  loading = ref(false),
  error = ref(''),
  loaded = ref(false)
const pendingId = ref<string | null>(null),
  preview = ref(''),
  confirming = ref<string | null>(null)
let active = true
const valid = () => active && !!token && getAccessToken() === token
const controller = new globalThis.AbortController()
watch(busy, (value) => emit('busy', value), { flush: 'sync' })
watch(pendingId, (value) => emit('pending', !!value), { flush: 'sync' })
const locked = computed(() => busy.value || props.disabled)

async function load() {
  if (!valid()) return
  loading.value = true
  error.value = ''
  try {
    const rows = await fetchWindowPhotos(props.id, controller.signal)
    if (valid()) {
      photos.value = rows
      loaded.value = true
    }
  } catch (reason) {
    if (valid()) error.value = toErrorMessage(reason)
  } finally {
    if (valid()) loading.value = false
  }
}
function releasePreview() {
  if (preview.value) globalThis.URL.revokeObjectURL(preview.value)
  preview.value = ''
}
/** 入册失败不能重新上传，保留原文件回执，重复确认不会增加照片数量。 */
async function bindPending() {
  if (!pendingId.value || !valid()) return
  const rows = await addWindowPhoto(props.id, pendingId.value, token!)
  if (!valid()) return
  photos.value = rows
  pendingId.value = null
  releasePreview()
  emit('changed')
}
async function choose(event: globalThis.Event) {
  const input = event.target as globalThis.HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (
    !file ||
    locked.value ||
    loading.value ||
    !loaded.value ||
    !valid() ||
    pendingId.value ||
    photos.value.length >= 5
  )
    return
  busy.value = true
  error.value = ''
  try {
    const jpeg = await compressPhoto(file)
    if (!valid()) return
    preview.value = globalThis.URL.createObjectURL(jpeg)
    const fileId = await uploadImage(jpeg, token!, 'WINDOW')
    if (!valid()) {
      void cancelImage(fileId, token!).catch(() => {})
      return
    }
    pendingId.value = fileId
    await bindPending()
  } catch (reason) {
    if (valid()) error.value = toErrorMessage(reason)
    if (!pendingId.value) releasePreview()
  } finally {
    busy.value = false
  }
}
async function retry() {
  if (locked.value || !valid()) return
  busy.value = true
  error.value = ''
  try {
    await bindPending()
  } catch (reason) {
    if (valid()) error.value = toErrorMessage(reason)
  } finally {
    busy.value = false
  }
}
async function remove() {
  const id = confirming.value
  if (!id || locked.value || !valid()) return
  busy.value = true
  error.value = ''
  try {
    await removeWindowPhoto(props.id, id, token!)
    if (!valid()) return
    confirming.value = null
    emit('changed')
    await load()
  } catch (reason) {
    if (valid()) error.value = toErrorMessage(reason)
  } finally {
    busy.value = false
  }
}
watch(authToken, () => {
  if (!valid()) emit('close')
})
onBeforeRouteLeave(() => !busy.value)
onBeforeRouteUpdate(() => !busy.value)
onBeforeUnmount(() => {
  active = false
  controller.abort()
  releasePreview()
  // 入册已成功但响应丢失时取消会被服务端拒绝，不能误删正式图片；临时文件仍可回收。
  if (pendingId.value) void cancelImage(pendingId.value, token!).catch(() => {})
})
void load()
</script>

<template>
  <section class="window-photo-management" :aria-label="`${name}的窗口照片`">
    <div class="photo-heading">
      <h2>窗口照片</h2>
      <span v-if="loaded">{{ photos.length }} / 5 张</span>
    </div>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="loading" role="status">正在读取照片…</p>
    <button
      v-if="!loading && (!loaded || (!!error && !pendingId))"
      class="photo-retry"
      type="button"
      :disabled="locked"
      @click="load"
    >
      重试读取照片
    </button>
    <div class="photos">
      <figure v-for="photo in photos" :key="photo.id">
        <img :src="photo.url" :alt="`${name}的窗口照片`" />
        <figcaption v-if="photo.cover">封面</figcaption>
        <button
          class="remove-photo"
          type="button"
          aria-label="删除照片"
          :disabled="locked || !!pendingId || loading"
          @click="confirming = photo.id"
        >
          ×
        </button>
      </figure>
      <figure v-if="preview">
        <img :src="preview" alt="本次上传预览" />
        <figcaption>等待保存</figcaption>
      </figure>
      <label
        v-if="loaded && photos.length < 5 && !pendingId"
        class="upload"
        :class="{ disabled: locked || loading }"
      >
        <span aria-hidden="true">＋</span><span>上传照片</span>
        <input
          type="file"
          aria-label="上传窗口照片"
          accept="image/jpeg,image/png,image/webp"
          :disabled="locked || loading"
          @change="choose"
        />
      </label>
    </div>
    <p class="photo-note">首张为封面 · 上传、删除后立即保存</p>
    <p v-if="loaded && !photos.length && !preview" class="photo-empty">暂无窗口照片</p>
    <p v-if="busy" role="status">正在处理，请稍候…</p>
    <div v-if="pendingId" class="photo-feedback">
      <p>图片已上传，保存结果尚未确认。请重试确认。</p>
      <button type="button" :disabled="locked" @click="retry">重试保存照片</button>
    </div>
    <div v-if="confirming" class="photo-feedback" role="group" aria-label="确认删除照片">
      <p>删除这张照片？删除后无法恢复。</p>
      <div class="photo-confirm-actions">
        <button class="photo-danger" type="button" :disabled="locked" @click="remove">
          确认删除照片</button
        ><button type="button" :disabled="locked" @click="confirming = null">取消删除</button>
      </div>
    </div>
  </section>
</template>
<style scoped>
.window-photo-management {
  min-width: 0;
}
.photo-heading {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  margin-bottom: 24px;
}
h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}
.photo-heading span,
p {
  color: var(--admin-muted);
  font-size: 12px;
  line-height: 1.8;
}
p {
  margin: 12px 0;
}
.photos {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
figure,
.upload {
  position: relative;
  min-width: 0;
  aspect-ratio: 1.12;
  margin: 0;
  overflow: hidden;
  border-radius: 6px;
  background: var(--admin-surface-muted);
}
img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
figcaption {
  position: absolute;
  left: 8px;
  bottom: 8px;
  padding: 2px 7px;
  border-radius: 4px;
  font-size: 11px;
  color: white;
  background: #29313bbd;
}
button {
  cursor: pointer;
  font: inherit;
  border: 1px solid var(--admin-border);
  padding: 8px 12px;
  background: white;
  border-radius: 5px;
  color: var(--admin-secondary);
}
button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.remove-photo {
  position: absolute;
  right: 7px;
  top: 7px;
  display: grid;
  place-items: center;
  width: 25px;
  height: 25px;
  min-height: 0;
  padding: 0;
  border: 0;
  border-radius: 50%;
  font-size: 17px;
  background: #fffffff2;
}
.window-photo-management .upload {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  gap: 8px;
  border: 1px dashed #d5dae0;
  cursor: pointer;
  font-size: 13px;
  color: var(--admin-secondary);
}
.upload span:first-child {
  font-size: 26px;
  font-weight: 300;
  line-height: 1;
}
.upload input {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0;
  cursor: pointer;
  padding: 0;
}
.upload:focus-within {
  outline: 2px solid var(--admin-focus);
  outline-offset: 3px;
}
.upload.disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.photo-feedback {
  padding: 10px 12px;
  margin-top: 15px;
  background: var(--admin-surface-muted);
  border-radius: 6px;
}
.photo-confirm-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.photo-danger,
[role='alert'] {
  color: var(--admin-danger);
}
.photo-retry {
  margin-bottom: 12px;
}
</style>
