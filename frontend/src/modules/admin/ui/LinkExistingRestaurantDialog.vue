<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { searchExistingRestaurants, type ExistingRestaurant } from '../api/restaurantRequestsApi'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { RestaurantManagementPreview } from '@/modules/restaurant'

/** 每次打开独立核对目标；取消和迟到结果不能复用上一次申请的选择。 */
const props = defineProps<{
  restaurantName: string
  restaurantType: ExistingRestaurant['restaurantType']
  submitting: boolean
  error: string
}>()
const emit = defineEmits<{ close: []; confirm: [restaurantId: string] }>()
const dialog = ref<globalThis.HTMLDialogElement>()
const query = ref(props.restaurantName),
  page = ref(1),
  totalPages = ref(0)
const rows = ref<ExistingRestaurant[]>([]),
  selected = ref<ExistingRestaurant | null>(null)
const loading = ref(false),
  searchError = ref('')
const preview = ref<ExistingRestaurant | null>(null)
const previewBack = ref<globalThis.HTMLButtonElement>()
let previewTrigger: globalThis.HTMLButtonElement | undefined
let abort: globalThis.AbortController | undefined,
  version = 0,
  timer: ReturnType<typeof globalThis.setTimeout>

/** 改关键字立即清空选择；版本号与取消请求共同阻止旧结果覆盖当前核对对象。 */
async function search() {
  const current = ++version
  abort?.abort()
  selected.value = null
  rows.value = []
  totalPages.value = 0
  searchError.value = ''
  if (!query.value.trim()) {
    loading.value = false
    return
  }
  loading.value = true
  abort = new globalThis.AbortController()
  try {
    const result = await searchExistingRestaurants(
      query.value.trim(),
      props.restaurantType,
      page.value,
      abort.signal,
    )
    if (current !== version) return
    rows.value = result.records
    totalPages.value = result.accessiblePages ?? result.totalPages
  } catch (error) {
    if (current === version) searchError.value = toErrorMessage(error)
  } finally {
    if (current === version) loading.value = false
  }
}
watch(query, () => {
  ++version
  abort?.abort()
  selected.value = null
  rows.value = []
  loading.value = !!query.value.trim()
  totalPages.value = 0
  searchError.value = ''
  page.value = 1
  globalThis.clearTimeout(timer)
  timer = globalThis.setTimeout(() => void search(), 250)
})
function changePage(value: number) {
  page.value = value
  void search()
}
function close() {
  if (props.submitting) {
    return
  }
  if (preview.value) {
    void closePreview()
    return
  }
  emit('close')
}
/** 在同一弹窗内核对资料，保留列表DOM以恢复搜索、分页、选择和滚动位置。 */
async function showPreview(row: ExistingRestaurant, event: globalThis.MouseEvent) {
  if (props.submitting) {
    return
  }
  previewTrigger = event.currentTarget as globalThis.HTMLButtonElement
  preview.value = row
  await nextTick()
  previewBack.value?.focus()
}
async function closePreview() {
  preview.value = null
  await nextTick()
  previewTrigger?.focus()
}
/** 直接处理当前弹窗的Esc，避免浏览器连续取消时跳过详情返回；相册自己的弹窗仍自行关闭。 */
function handleEscape(event: globalThis.KeyboardEvent) {
  if (
    !(event.target instanceof globalThis.Element) ||
    event.target.closest('dialog') !== dialog.value
  ) {
    return
  }
  event.preventDefault()
  event.stopPropagation()
  close()
}
onMounted(async () => {
  await nextTick()
  dialog.value?.showModal()
  void search()
})
onBeforeUnmount(() => {
  ++version
  globalThis.clearTimeout(timer)
  abort?.abort()
  dialog.value?.close()
})
</script>

<template>
  <dialog
    ref="dialog"
    class="link-existing-dialog"
    aria-labelledby="link-existing-title"
    @cancel.prevent="close"
    @keydown.esc="handleEscape"
  >
    <div class="dialog-layout">
      <header>
        <div class="dialog-heading">
          <button
            v-if="preview"
            ref="previewBack"
            type="button"
            aria-label="返回店铺列表"
            @click="closePreview"
          >
            ←
          </button>
          <h2 id="link-existing-title">{{ preview ? '店铺资料' : '店铺已收录' }}</h2>
        </div>
        <button
          type="button"
          :aria-label="preview ? '关闭店铺详情' : '关闭店铺选择'"
          :disabled="submitting"
          @click="close"
        >
          ×
        </button>
      </header>
      <div v-show="!preview" class="dialog-content">
        <div class="submission-source">
          <span>当前投稿</span>
          <p>{{ restaurantName }}</p>
        </div>
        <label class="search-label"
          ><svg viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="10.5" cy="10.5" r="6.5" />
            <path d="m16 16 4.5 4.5" /></svg
          ><input
            v-model="query"
            type="search"
            maxlength="80"
            :aria-label="`搜索已收录${restaurantType === 'CANTEEN' ? '食堂' : '店铺'}`"
            :placeholder="`搜索已收录${restaurantType === 'CANTEEN' ? '食堂' : '店铺'}`"
            :disabled="submitting"
        /></label>
        <div class="results" :aria-busy="loading">
          <p v-if="loading" role="status">正在查找店铺…</p>
          <div v-else-if="searchError" role="alert">
            <p>{{ searchError }}</p>
            <button type="button" @click="search">重试搜索</button>
          </div>
          <p v-else-if="!query.trim()">输入名称或地址后查找。</p>
          <p v-else-if="!rows.length">没有找到对应店铺，请调整关键词。</p>
          <div
            v-for="row in rows"
            v-else
            :key="row.id"
            class="candidate"
            :class="{ chosen: selected?.id === row.id }"
          >
            <button
              type="button"
              class="candidate-select"
              :aria-pressed="selected?.id === row.id"
              :disabled="submitting"
              @click="selected = row"
            >
              <strong>{{ row.name }}</strong>
              <span class="candidate-address">{{ row.address || '地址未填写' }}</span>
              <svg
                v-if="selected?.id === row.id"
                class="selected-check"
                viewBox="0 0 24 24"
                aria-hidden="true"
              >
                <path d="m5 12 4 4L19 6" />
              </svg>
            </button>
            <button
              type="button"
              class="candidate-view"
              :disabled="submitting"
              @click="showPreview(row, $event)"
            >
              查看店铺 ›
            </button>
          </div>
        </div>
        <nav v-if="totalPages > 1" aria-label="已有店铺分页">
          <button
            type="button"
            :disabled="submitting || loading || page <= 1"
            @click="changePage(page - 1)"
          >
            上一页</button
          ><span>{{ page }} / {{ totalPages }}</span
          ><button
            type="button"
            :disabled="submitting || loading || page >= totalPages"
            @click="changePage(page + 1)"
          >
            下一页
          </button>
        </nav>
      </div>
      <div v-if="preview" class="preview-content">
        <RestaurantManagementPreview :id="preview.id" />
      </div>
      <div v-show="!preview" class="dialog-bottom">
        <p class="effect">确认后结束申请，附带点评将发布到所选店铺。</p>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <footer>
          <button type="button" :disabled="submitting" @click="close">取消</button
          ><button
            type="button"
            class="confirm"
            :disabled="submitting || loading || !selected"
            @click="selected && emit('confirm', selected.id)"
          >
            {{ submitting ? '正在处理…' : '确认已收录' }}
          </button>
        </footer>
      </div>
    </div>
  </dialog>
</template>

<style scoped>
.link-existing-dialog {
  width: min(560px, calc(100vw - 32px));
  max-width: min(560px, calc(100vw - 32px));
  max-height: 90vh;
  max-height: 90dvh;
  box-sizing: border-box;
  border: 1px solid var(--admin-border);
  border-radius: 14px;
  padding: 0;
  overflow: hidden;
  color: var(--admin-text);
  background: var(--admin-surface);
  box-shadow: 0 24px 90px var(--admin-shadow);
}
.dialog-layout {
  display: flex;
  flex-direction: column;
  max-height: calc(90vh - 2px);
  max-height: calc(90dvh - 2px);
}
.dialog-content {
  min-height: 0;
  overflow: auto;
  padding: 0 24px 20px;
}
.preview-content {
  min-height: 0;
  overflow: auto;
  padding: 0 24px 24px;
}
.dialog-heading {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.dialog-bottom {
  flex-shrink: 0;
  border-top: 1px solid var(--admin-divider);
  padding: 16px 24px 20px;
}
dialog::backdrop {
  background: var(--admin-backdrop);
}
header,
footer,
nav {
  display: flex;
  align-items: center;
  gap: 12px;
}
header {
  justify-content: space-between;
  flex-shrink: 0;
  padding: 20px 24px 18px;
}
.submission-source > span {
  font-size: 12px;
  color: var(--admin-muted);
}
.submission-source p {
  margin: 5px 0 18px;
  font-size: 14px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
h2 {
  margin: 0;
  font-size: 22px;
}
button {
  cursor: pointer;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  background: var(--admin-surface);
  color: inherit;
  padding: 9px 14px;
  font: inherit;
}
button:disabled {
  opacity: 0.5;
  cursor: default;
}
header button {
  border: 0;
  font-size: 24px;
  width: 40px;
  height: 40px;
  padding: 0;
  color: var(--admin-muted);
}
.effect {
  margin: 0 0 16px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--admin-secondary);
}
.search-label {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 0 13px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  font-size: 13px;
}
.search-label svg {
  flex-shrink: 0;
  width: 17px;
  height: 17px;
  fill: none;
  stroke: var(--admin-muted);
  stroke-width: 1.6;
  stroke-linecap: round;
}
.search-label input {
  width: 100%;
  min-width: 0;
  border: 0;
  padding: 12px 0;
  color: inherit;
  background: transparent;
  font: inherit;
  outline: none;
  box-shadow: none;
}
.results {
  min-height: 112px;
  max-height: 34vh;
  max-height: 34dvh;
  overflow: auto;
  margin: 16px 0 0;
  font-size: 13px;
  color: var(--admin-secondary);
}
.candidate {
  position: relative;
  padding: 14px 15px;
  margin-bottom: 10px;
  border: 1px solid var(--admin-border);
  border-radius: 10px;
}
.candidate.chosen {
  background: var(--admin-accent-soft);
}
.candidate-select {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 20px;
  gap: 6px;
  width: 100%;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
  text-align: left;
  color: var(--admin-text);
}
.candidate-select::after {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: 10px;
}
.candidate-select strong {
  font-size: 15px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.candidate-address {
  grid-column: 1;
  font-size: 12px;
  color: var(--admin-secondary);
  overflow-wrap: anywhere;
}
.selected-check {
  grid-column: 2;
  grid-row: 1;
  width: 20px;
  height: 20px;
  stroke: var(--admin-accent-text);
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
  fill: none;
}
.candidate-view {
  position: relative;
  display: inline-block;
  margin-top: 8px;
  font-size: 12px;
  color: var(--admin-accent-text);
  white-space: nowrap;
  text-decoration: none;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
}
nav {
  justify-content: center;
  font-size: 13px;
}
footer {
  justify-content: flex-end;
}
.confirm {
  background: var(--admin-primary);
  color: var(--admin-surface);
  border-color: var(--admin-primary);
}
.error {
  color: var(--admin-danger);
  font-size: 14px;
}
button:focus-visible {
  outline: 2px solid var(--admin-focus);
  outline-offset: 3px;
}
.candidate-select:focus-visible {
  outline: none;
}
.candidate-select:focus-visible::after {
  outline: 2px solid var(--admin-focus);
  outline-offset: -3px;
}
@media (max-width: 480px) {
  header {
    padding: 16px 18px 12px;
  }
  .dialog-content {
    padding: 0 18px 16px;
  }
  .preview-content {
    padding: 0 18px 18px;
  }
  .dialog-bottom {
    padding: 14px 18px 18px;
  }
}
</style>
