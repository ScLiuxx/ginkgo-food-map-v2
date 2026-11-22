<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { getAccessToken } from '@/shared/api/authSession'
import {
  fetchManagedWindows,
  fetchWindowPhotos,
  type CanteenWindow,
  type WindowPhoto,
} from '@/modules/restaurant/api/canteenWindowApi'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'

const props = defineProps<{ canteenId: string; id: string }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<globalThis.HTMLDialogElement>()
const row = ref<CanteenWindow>()
const photos = ref<WindowPhoto[]>([])
const loading = ref(true),
  error = ref(''),
  photoError = ref('')
const token = getAccessToken()
const controller = new globalThis.AbortController()
let active = true
const valid = () => active && !!token && token === getAccessToken()

async function loadPhotos() {
  if (!valid() || !row.value || row.value.deleted) return
  photoError.value = ''
  try {
    const result = await fetchWindowPhotos(props.id, controller.signal)
    if (valid()) photos.value = result
  } catch (reason) {
    if (valid()) photoError.value = toErrorMessage(reason)
  }
}
async function load() {
  if (!valid()) return
  loading.value = true
  error.value = ''
  try {
    const items = await fetchManagedWindows(props.canteenId, token!, controller.signal)
    if (!valid()) return
    row.value = items.find((item) => item.id === props.id && item.canteenId === props.canteenId)
    if (!row.value) throw new Error('窗口已不可用，请返回列表核对。')
    await loadPhotos()
  } catch (reason) {
    if (valid()) error.value = toErrorMessage(reason)
  } finally {
    if (valid()) loading.value = false
  }
}
onMounted(() => {
  dialog.value?.showModal()
  void load()
})
onBeforeUnmount(() => {
  active = false
  controller.abort()
  dialog.value?.close()
})
</script>
<template>
  <dialog
    ref="dialog"
    class="window-preview-dialog"
    aria-labelledby="window-preview-title"
    @cancel.prevent="emit('close')"
  >
    <header>
      <h2 id="window-preview-title">{{ row?.name || '窗口资料' }}</h2>
      <button type="button" aria-label="关闭窗口资料" @click="emit('close')">×</button>
    </header>
    <p v-if="loading" role="status">正在读取窗口资料…</p>
    <div v-else-if="error" role="alert">
      {{ error }} <button type="button" @click="load">重试读取</button>
    </div>
    <template v-else-if="row">
      <p class="preview-context">
        {{ row.canteenName }} · {{ row.floorNumber }} 楼 ·
        {{ row.deleted ? '已删除' : row.businessStatus === 'CLOSED' ? '停业' : '营业' }}
      </p>
      <p v-if="row.description" class="preview-description">{{ row.description }}</p>
      <dl>
        <div>
          <dt>人均</dt>
          <dd>
            {{
              row.priceKind === 'UNKNOWN' || row.pricePerPerson === null
                ? '未知'
                : `${row.priceKind === 'ESTIMATE' ? '约 ' : ''}¥${row.pricePerPerson}`
            }}
          </dd>
        </div>
        <div>
          <dt>评分</dt>
          <dd>{{ row.score === null ? '待评价' : (row.score / 2).toFixed(1) }}</dd>
        </div>
        <div>
          <dt>点评</dt>
          <dd>{{ row.reviewCount }} 条</dd>
        </div>
      </dl>
      <div v-if="photoError" role="alert">
        {{ photoError }} <button type="button" @click="loadPhotos">重试读取照片</button>
      </div>
      <div v-if="!row.deleted && photos.length" class="preview-photos">
        <img
          v-for="photo in photos"
          :key="photo.id"
          :src="photo.url"
          :alt="`${row.name}的窗口照片`"
        />
      </div>
    </template>
  </dialog>
</template>
<style scoped>
.window-preview-dialog {
  box-sizing: border-box;
  width: min(640px, calc(100vw - 32px));
  max-height: calc(100vh - 32px);
  max-height: calc(100dvh - 32px);
  overflow: auto;
  border: 0;
  border-radius: 10px;
  padding: 24px;
  color: var(--admin-text);
  background: var(--admin-surface);
}
.window-preview-dialog::backdrop {
  background: var(--admin-backdrop);
}
header {
  position: sticky;
  top: -24px;
  display: flex;
  justify-content: space-between;
  gap: 20px;
  align-items: flex-start;
  padding: 12px 0;
  background: white;
}
h2 {
  margin: 0;
  font-size: 20px;
  overflow-wrap: anywhere;
}
button {
  border: 0;
  background: none;
  color: var(--admin-secondary);
  cursor: pointer;
  font: inherit;
}
header button {
  font-size: 24px;
  padding: 0 0 0 12px;
  line-height: 1;
}
p {
  line-height: 1.8;
  font-size: 14px;
}
.preview-context {
  font-size: 13px;
  color: var(--admin-muted);
}
.preview-description {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
dl {
  display: flex;
  gap: 30px;
  margin: 22px 0;
  flex-wrap: wrap;
}
dt {
  font-size: 12px;
  color: var(--admin-muted);
}
dd {
  margin: 6px 0 0;
  font-size: 14px;
}
.preview-photos {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
img {
  width: 100%;
  aspect-ratio: 1.2;
  object-fit: cover;
  border-radius: 6px;
}
[role='alert'] {
  font-size: 13px;
  color: var(--admin-danger);
}
</style>
