<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
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
const props = defineProps<{ id: string; name: string }>()
const emit = defineEmits<{ close: []; changed: []; busy: [value: boolean] }>()
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
  if (!file || busy.value || !valid() || pendingId.value || photos.value.length >= 5) return
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
  if (busy.value || !valid()) return
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
  if (!id || busy.value || !valid()) return
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
    <h2>{{ name }} · 窗口照片</h2>
    <p>最多5张，首张作为封面。上传和删除后立即保存。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="loading" role="status">正在读取照片…</p>
    <button v-if="!loading && (!loaded || (!!error && !pendingId))" type="button" @click="load">
      重试读取照片
    </button>
    <div class="photos">
      <figure v-for="photo in photos" :key="photo.id">
        <img :src="photo.url" :alt="`${name}的窗口照片`" />
        <figcaption>{{ photo.cover ? '封面' : '窗口照片' }}</figcaption>
        <button type="button" :disabled="busy || !!pendingId" @click="confirming = photo.id">
          删除照片
        </button>
      </figure>
      <figure v-if="preview">
        <img :src="preview" alt="本次上传预览" />
        <figcaption>等待保存</figcaption>
      </figure>
    </div>
    <p v-if="loaded && !photos.length && !preview">暂无窗口照片</p>
    <label v-if="loaded && photos.length < 5 && !pendingId" class="upload">
      上传照片<input
        type="file"
        accept="image/jpeg,image/png,image/webp"
        :disabled="busy || loading"
        @change="choose"
      />
    </label>
    <p v-if="busy" role="status">正在处理，请稍候…</p>
    <div v-if="pendingId">
      <p>图片已上传，保存结果尚未确认。请重试确认。</p>
      <button type="button" :disabled="busy" @click="retry">重试保存照片</button>
    </div>
    <div v-if="confirming" role="group" aria-label="确认删除照片">
      <p>确认删除这张照片？删除后无法恢复。</p>
      <button type="button" :disabled="busy" @click="remove">确认删除照片</button>
      <button type="button" :disabled="busy" @click="confirming = null">取消删除</button>
    </div>
    <button type="button" :disabled="busy" @click="emit('close')">关闭照片管理</button>
  </section>
</template>

<style scoped>
.window-photo-management {
  border: 1px solid #e5e8ec;
  border-radius: 8px;
  padding: 20px;
  margin-top: 20px;
}
p {
  color: #687583;
  line-height: 1.8;
}
.photos {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}
figure {
  margin: 0;
  width: 140px;
}
img {
  width: 140px;
  height: 110px;
  object-fit: cover;
  border-radius: 6px;
  background: #f3f4f5;
}
figcaption {
  font-size: 12px;
  color: #687583;
  margin: 6px 0;
}
button {
  padding: 9px 12px;
  margin: 5px 5px 5px 0;
  border: 1px solid #dfe3e7;
  border-radius: 5px;
  background: white;
  cursor: pointer;
}
button:disabled {
  opacity: 0.5;
}
.upload {
  display: grid;
  gap: 8px;
  margin: 16px 0;
}
input {
  max-width: 100%;
}
[role='alert'] {
  color: #a44d2e;
}
</style>
