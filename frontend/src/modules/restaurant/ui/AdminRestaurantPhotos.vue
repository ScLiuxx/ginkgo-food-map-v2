<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { authToken, getAccessToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import type { DraftPhoto } from '@/modules/file/model/useDraftPhotos'
import { useSubmissionPhotos } from '../model/useSubmissionPhotos'
import { fetchRestaurantAlbum, fetchRestaurantAlbumImage } from '../api/restaurantAlbumApi'
import {
  fetchReviewPhotoCandidates,
  readReviewPhoto,
  type RestaurantPhotoSelection,
  type ReviewPhotoCandidate,
} from '../api/restaurantPhotoManagementApi'

const props = withDefaults(defineProps<{ id?: string; disabled?: boolean }>(), {
  id: '',
  disabled: false,
})
const emit = defineEmits<{
  selection: [value: RestaurantPhotoSelection[]]
  state: [value: { loading: boolean; busy: boolean; dirty: boolean; error: string }]
}>()
interface Row {
  key: string
  photoId?: string
  draft?: DraftPhoto
  url: string
  source: string
  failed?: boolean
}
const token = getAccessToken()
const active = () => (token && token === getAccessToken() ? token : null)
const draft = useSubmissionPhotos('RESTAURANT', active)
const rows = ref<Row[]>([]),
  loading = ref(false),
  error = ref(''),
  baseline = ref('')
const picker = ref<globalThis.HTMLDialogElement>(),
  file = ref<globalThis.HTMLInputElement>()
const opened = ref(false),
  copying = ref(false),
  candidateLoading = ref(false),
  candidateError = ref('')
const candidates = ref<ReviewPhotoCandidate[]>([]),
  selected = ref(new Set<string>()),
  page = ref(1),
  hasMore = ref(false)
let controller = new globalThis.AbortController(),
  pickerController = new globalThis.AbortController(),
  generation = 0,
  pickerGeneration = 0
const urls = new Set<string>()
const signature = computed(() => JSON.stringify(rows.value.map((row) => row.key)))
const dirty = computed(() => signature.value !== baseline.value)
const busy = computed(() => copying.value || draft.processing.value || draft.pending.value)
const blocked = computed(() => props.disabled || !active())
const key = (photo: ReviewPhotoCandidate) => `${photo.reviewId}:${photo.position}`

watch(
  draft.photos,
  (photos) => {
    rows.value = rows.value.filter((row) => !row.draft || photos.some((p) => p.url === row.key))
    for (const photo of photos)
      if (!rows.value.some((row) => row.key === photo.url))
        rows.value.push({ key: photo.url, draft: photo, url: photo.url, source: '本地上传' })
  },
  { deep: true, flush: 'sync' },
)
watch(
  [signature, loading, busy, error, draft.failed, draft.error, authToken],
  () => {
    emit(
      'selection',
      rows.value.map((row) =>
        row.photoId ? { photoId: row.photoId } : { fileId: row.draft?.fileId },
      ),
    )
    emit('state', {
      loading: loading.value,
      busy: busy.value,
      dirty: dirty.value,
      error:
        error.value ||
        draft.error.value ||
        (draft.failed.value ? '有照片上传失败，请重试或移除。' : ''),
    })
  },
  { immediate: true, flush: 'sync' },
)

async function loadImage(row: Row, ticket: number) {
  try {
    const blob = await fetchRestaurantAlbumImage(props.id, row.photoId!, controller.signal)
    if (ticket !== generation || !active()) return
    if (row.url && urls.has(row.url)) {
      globalThis.URL.revokeObjectURL(row.url)
      urls.delete(row.url)
    }
    row.url = globalThis.URL.createObjectURL(blob)
    urls.add(row.url)
    row.failed = false
  } catch {
    if (ticket === generation && !controller.signal.aborted) row.failed = true
  }
}
async function load() {
  const ticket = ++generation
  controller.abort()
  controller = new globalThis.AbortController()
  for (const url of urls) globalThis.URL.revokeObjectURL(url)
  urls.clear()
  draft.clear()
  rows.value = []
  baseline.value = '[]'
  loading.value = !!props.id
  error.value = ''
  if (!props.id || !active()) return
  try {
    let cursor: string | null = null
    const collected: Row[] = []
    const cursors = new Set<string>()
    do {
      const result = await fetchRestaurantAlbum(props.id, cursor, controller.signal)
      if (ticket !== generation || !active()) return
      collected.push(
        ...result.records.map((photo) => ({
          key: photo.id,
          photoId: photo.id,
          url: '',
          source: '商户照片',
        })),
      )
      if (result.nextCursor && cursors.has(result.nextCursor))
        throw new Error('相册分页异常，请重新读取。')
      cursor = result.nextCursor
      if (cursor) cursors.add(cursor)
      if (collected.length > 1000) throw new Error('相册过大，请联系维护人员处理。')
    } while (cursor)
    rows.value = collected
    baseline.value = signature.value
    let index = 0
    await Promise.all(
      Array.from({ length: Math.min(4, collected.length) }, async () => {
        while (index < rows.value.length && ticket === generation) {
          const row = rows.value[index++]!
          await loadImage(row, ticket)
        }
      }),
    )
  } catch (reason) {
    if (ticket === generation && !controller.signal.aborted) error.value = toErrorMessage(reason)
  } finally {
    if (ticket === generation) loading.value = false
  }
}
function remove(row: Row) {
  if (blocked.value || busy.value) return
  if (row.draft) draft.remove(draft.photos.value.findIndex((p) => p.url === row.key))
  else rows.value = rows.value.filter((p) => p !== row)
}
function cover(index: number) {
  if (!blocked.value && !busy.value) rows.value.unshift(rows.value.splice(index, 1)[0]!)
}
async function addLocal(files: globalThis.FileList | null) {
  if (!blocked.value) await draft.addFiles(files)
}
function changeFiles(event: globalThis.Event) {
  const input = event.target as globalThis.HTMLInputElement
  void addLocal(input.files)
  input.value = ''
}
function turnPage(delta: number) {
  page.value += delta
  void loadCandidates()
}
async function loadCandidates() {
  const ticket = ++pickerGeneration
  pickerController.abort()
  pickerController = new globalThis.AbortController()
  candidateLoading.value = true
  candidateError.value = ''
  selected.value = new Set()
  try {
    const data = await fetchReviewPhotoCandidates(
      props.id,
      page.value,
      token!,
      pickerController.signal,
    )
    if (ticket === pickerGeneration && opened.value && active()) {
      candidates.value = data.records
      hasMore.value = data.hasMore
    }
  } catch (reason) {
    if (ticket === pickerGeneration && !pickerController.signal.aborted)
      candidateError.value = toErrorMessage(reason)
  } finally {
    if (ticket === pickerGeneration) candidateLoading.value = false
  }
}
async function openPicker() {
  if (blocked.value || !props.id) return
  opened.value = true
  page.value = 1
  candidates.value = []
  await nextTick()
  picker.value?.showModal()
  void loadCandidates()
}
function closePicker() {
  if (copying.value) return
  opened.value = false
  pickerGeneration++
  pickerController.abort()
  picker.value?.close()
}
function toggle(photo: ReviewPhotoCandidate) {
  const next = new Set(selected.value)
  if (next.has(key(photo))) next.delete(key(photo))
  else next.add(key(photo))
  selected.value = next
}
async function addSelected() {
  if (copying.value || blocked.value) return
  const chosen = candidates.value.filter((p) => selected.value.has(key(p)))
  if (chosen.length + draft.photos.value.length > 5) {
    candidateError.value = '每次保存最多新增5张照片，请减少选择或先保存。'
    return
  }
  copying.value = true
  candidateError.value = ''
  const oldCount = draft.photos.value.length,
    ticket = generation
  try {
    await draft.copyExisting(
      chosen.map((_, i) => i),
      (i) => readReviewPhoto(chosen[i]!, pickerController.signal),
    )
    if (ticket !== generation || !active()) return
    for (const photo of draft.photos.value.slice(oldCount)) {
      const row = rows.value.find((row) => row.key === photo.url)
      if (row) row.source = '来自点评'
    }
    copying.value = false
    closePicker()
  } catch (reason) {
    candidateError.value = toErrorMessage(reason)
  } finally {
    copying.value = false
  }
}
function saved() {
  baseline.value = signature.value
  draft.clear(true)
}
defineExpose({ saved })
watch(() => props.id, load, { immediate: true })
watch(authToken, () => {
  if (!active()) {
    generation++
    controller.abort()
    copying.value = false
    closePicker()
    draft.clear()
    rows.value = []
  }
})
onBeforeUnmount(() => {
  generation++
  pickerGeneration++
  controller.abort()
  pickerController.abort()
  draft.clear()
  for (const url of urls) globalThis.URL.revokeObjectURL(url)
})
</script>

<template>
  <section class="admin-photo-manager" :aria-busy="loading || busy">
    <div class="photo-heading">
      <h2>商户照片</h2>
      <span>{{ rows.length }} 张</span>
    </div>
    <div class="photo-toolbar">
      <button
        type="button"
        :disabled="blocked || loading || busy || !!error"
        @click="file?.click()"
      >
        ＋ 从本地添加
      </button>
      <button
        type="button"
        :disabled="blocked || loading || busy || !!error || !id"
        @click="openPicker"
      >
        从点评中选择
      </button>
      <input
        ref="file"
        class="photo-file"
        type="file"
        accept="image/jpeg,image/png,image/webp"
        multiple
        aria-label="选择商户照片"
        @change="changeFiles"
      />
    </div>
    <p v-if="loading" role="status">正在读取相册…</p>
    <p v-if="error" class="photo-error" role="alert">
      {{ error }} <button type="button" :disabled="blocked" @click="load">重新读取</button>
    </p>
    <div v-if="!loading && !error" class="managed-photos">
      <article v-for="(row, index) in rows" :key="row.key" class="managed-photo">
        <div class="photo-preview">
          <img v-if="row.url && !row.failed" :src="row.url" :alt="`商户照片 ${index + 1}`" /><button
            v-else
            type="button"
            :disabled="blocked"
            @click="loadImage(row, generation)"
          >
            图片读取失败，重试
          </button>
          <span v-if="index === 0" class="cover-badge">封面</span
          ><button
            class="remove-photo"
            type="button"
            :aria-label="`移除第${index + 1}张商户照片`"
            :disabled="blocked || busy"
            @click="remove(row)"
          >
            ×
          </button>
        </div>
        <div class="managed-caption">
          <span>{{ row.source }}</span
          ><button v-if="index" type="button" :disabled="blocked || busy" @click="cover(index)">
            设为封面</button
          ><span v-else>当前封面</span>
        </div>
        <p v-if="row.draft?.state === 'uploading'" class="photo-note">上传中…</p>
        <p v-if="row.draft?.state === 'failed'" class="photo-error" role="alert">
          {{ row.draft.error }}
          <button type="button" :disabled="blocked" @click="draft.retry(row.draft)">
            重试上传
          </button>
        </p>
      </article>
    </div>
    <p v-if="!loading && !error && !rows.length" class="photo-note">暂无商户照片</p>
    <p v-if="draft.error.value" class="photo-error" role="alert">{{ draft.error.value }}</p>
    <dialog
      ref="picker"
      class="review-photo-picker"
      aria-labelledby="review-photo-picker-title"
      @cancel.prevent="closePicker"
    >
      <header>
        <h2 id="review-photo-picker-title">从点评中选择照片</h2>
        <button type="button" aria-label="关闭选图" :disabled="copying" @click="closePicker">
          ×
        </button>
      </header>
      <div class="candidate-scroll">
        <p v-if="candidateLoading" role="status">正在读取点评照片…</p>
        <p v-else-if="candidateError" role="alert" class="photo-error">
          {{ candidateError }}
          <button type="button" :disabled="copying" @click="loadCandidates">重试</button>
        </p>
        <p v-else-if="!candidates.length" class="photo-note">这家商户暂无点评照片</p>
        <div v-else class="candidate-grid">
          <button
            v-for="photo in candidates"
            :key="key(photo)"
            type="button"
            class="candidate-photo"
            :class="{ selected: selected.has(key(photo)) }"
            :aria-pressed="selected.has(key(photo))"
            :aria-label="`选择${photo.username}的点评照片${photo.position + 1}`"
            :disabled="copying"
            @click="toggle(photo)"
          >
            <div>
              <img :src="photo.url" alt="点评照片" loading="lazy" /><span>{{
                selected.has(key(photo)) ? '✓' : ''
              }}</span>
            </div>
            <small>{{ photo.username }}</small>
            <p>{{ photo.body }}</p>
          </button>
        </div>
      </div>
      <footer>
        <div class="candidate-paging">
          <button
            type="button"
            :disabled="page <= 1 || copying || candidateLoading"
            @click="turnPage(-1)"
          >
            上一页</button
          ><span>{{ page }}</span
          ><button
            type="button"
            :disabled="!hasMore || copying || candidateLoading || page >= 834"
            @click="turnPage(1)"
          >
            下一页
          </button>
        </div>
        <div>
          <span class="selection-count">已选 {{ selected.size }} 张</span
          ><button type="button" :disabled="copying" @click="closePicker">取消</button
          ><button
            class="confirm-photos"
            type="button"
            :disabled="!selected.size || copying || candidateLoading || blocked"
            @click="addSelected"
          >
            {{ copying ? '正在添加…' : '添加所选照片' }}
          </button>
        </div>
      </footer>
    </dialog>
  </section>
</template>

<style scoped>
.photo-heading,
.photo-toolbar,
.managed-caption {
  display: flex;
  align-items: center;
  gap: 10px;
}
.photo-heading h2 {
  font-size: 16px;
  margin: 0;
}
.photo-heading > span,
.managed-caption,
.photo-note {
  font-size: 12px;
  color: #85919d;
}
.photo-toolbar {
  margin: 20px 0;
  flex-wrap: wrap;
}
.photo-toolbar button {
  font-size: 13px;
  padding: 8px 12px;
  border: 1px solid #e1e5ea;
  border-radius: 6px;
  background: white;
  color: #626d7b;
  cursor: pointer;
}
.photo-file {
  display: none;
}
.managed-photos {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px 14px;
}
.photo-preview {
  position: relative;
  aspect-ratio: 1.5;
  background: #f6f7f9;
  border-radius: 7px;
  overflow: hidden;
  display: grid;
  place-items: center;
}
.photo-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.cover-badge {
  position: absolute;
  top: 8px;
  left: 8px;
  background: #fff6e7;
  color: #a56c2e;
  font-size: 11px;
  border-radius: 4px;
  padding: 1px 7px;
}
.photo-preview .remove-photo {
  position: absolute;
  top: 7px;
  right: 7px;
  width: 25px;
  height: 25px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: #29313b88;
  color: white;
  font-size: 18px;
  cursor: pointer;
}
.managed-caption {
  justify-content: space-between;
  margin-top: 6px;
  font-size: 11px;
}
.managed-caption button {
  border: 0;
  background: none;
  color: #697887;
  font: inherit;
  cursor: pointer;
}
.photo-error {
  color: #a64e3d;
  font-size: 12px;
}
.photo-error button {
  font: inherit;
  border: 0;
  background: none;
  color: inherit;
  text-decoration: underline;
  cursor: pointer;
}
.review-photo-picker {
  width: min(800px, calc(100% - 32px));
  max-height: 88vh;
  padding: 0;
  border: 0;
  border-radius: 11px;
  color: #29313b;
  box-shadow: 0 16px 70px #25313d33;
}
.review-photo-picker::backdrop {
  background: #25313d55;
}
.review-photo-picker[open] {
  display: flex;
  flex-direction: column;
}
.review-photo-picker header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 24px;
  border-bottom: 1px solid #edf0f3;
}
.review-photo-picker header h2 {
  font-size: 18px;
  margin: 0;
}
.review-photo-picker button {
  font: inherit;
  cursor: pointer;
}
.review-photo-picker header button {
  border: 0;
  background: none;
  font-size: 24px;
  color: #929daa;
}
.candidate-scroll {
  padding: 20px 24px;
  overflow: auto;
  min-height: 150px;
}
.candidate-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px 14px;
}
.candidate-photo {
  border: 0;
  background: white;
  padding: 0;
  text-align: left;
  min-width: 0;
}
.candidate-photo > div {
  position: relative;
}
.candidate-photo img {
  display: block;
  width: 100%;
  aspect-ratio: 1.5;
  object-fit: cover;
  border: 3px solid transparent;
  border-radius: 7px;
}
.candidate-photo.selected img {
  border-color: #d08a3c;
}
.candidate-photo > div > span {
  position: absolute;
  right: 10px;
  top: 10px;
  border: 1px solid white;
  background: #29313b33;
  color: white;
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border-radius: 50%;
}
.candidate-photo.selected > div > span {
  background: #b77831;
  border-color: #b77831;
}
.candidate-photo small {
  display: block;
  color: #87939f;
  font-size: 11px;
  margin-top: 6px;
}
.candidate-photo p {
  color: #687684;
  font-size: 12px;
  margin: 5px 0 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.review-photo-picker footer {
  padding: 16px 24px;
  border-top: 1px solid #edf0f3;
  display: flex;
  justify-content: space-between;
  gap: 14px;
  align-items: center;
}
.review-photo-picker footer > div {
  display: flex;
  gap: 9px;
  align-items: center;
}
.review-photo-picker footer button {
  padding: 8px 12px;
  border: 1px solid #e1e5ea;
  border-radius: 6px;
  background: white;
  color: #626d7b;
  font-size: 12px;
}
.review-photo-picker footer .confirm-photos {
  background: #b65f1d;
  color: white;
  border-color: #b65f1d;
}
.selection-count,
.candidate-paging {
  font-size: 11px;
  color: #8a96a3;
}
button:disabled {
  opacity: 0.5;
  cursor: default;
}
button:focus-visible {
  outline: 2px solid #969da7;
  outline-offset: 3px;
}
@media (max-width: 700px) {
  .candidate-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .review-photo-picker header,
  .candidate-scroll {
    padding: 18px;
  }
  .review-photo-picker footer {
    padding: 14px;
    flex-wrap: wrap;
  }
  .review-photo-picker footer > div:last-child {
    margin-left: auto;
  }
  .photo-toolbar button {
    font-size: 12px;
    padding: 8px 10px;
  }
  .managed-photos {
    gap: 14px 10px;
  }
  .review-photo-picker footer button {
    padding: 7px 9px;
  }
  .selection-count {
    display: none;
  }
}
</style>
