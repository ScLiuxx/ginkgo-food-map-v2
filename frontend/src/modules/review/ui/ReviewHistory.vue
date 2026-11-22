<script setup lang="ts">
import { computed, inject, onUnmounted, provide, ref, shallowRef, useId } from 'vue'
import { useMediaQuery } from '@/shared/lib/useMediaQuery'
import { reviewHistoryHostKey } from '../model/reviewHistoryHost'
import { reviewGatewayKey, unavailableReviewGateway } from '../model/review'
import { replyScopeVisibleKey } from '../model/replyEditor'
import type { ReviewItem } from '../model/review'
import ReviewEntry from './ReviewEntry.vue'

/** 历史只在独立面板内展示；关闭保留记录和回复草稿，不把旧卡片嵌回主列表。 */
const props = defineProps<{
  restaurantId: string
  item: ReviewItem
  viewerId?: string
  starScale?: boolean
  storeName?: string
  /** 折叠菜单选中后原按钮已隐藏，关闭历史时回到仍可见的更多入口。 */
  restoreFocus?: () => void
}>()
const emit = defineEmits<{
  photo: [url: string]
  refresh: []
  delete: [item: ReviewItem]
}>()
const gateway = inject(reviewGatewayKey, unavailableReviewGateway)
provide(reviewGatewayKey, gateway)
const open = ref(false),
  loading = ref(false),
  loaded = ref(false),
  error = ref('')
const records = ref<ReviewItem[]>([]),
  hasMore = ref(true)
const dialog = ref<globalThis.HTMLDialogElement>(),
  trigger = ref<globalThis.HTMLButtonElement>()
const titleId = 'history-title-' + useId()
const desktop = useMediaQuery('(min-width: 800px)')
const detailHost = ref<globalThis.HTMLElement>()
const activeId = ref<string>()
const back = shallowRef<() => void>()
provide(reviewHistoryHostKey, {
  desktop,
  storeName: computed(() => props.storeName),
  target: detailHost,
  activeId,
  back,
  close,
})
provide(
  replyScopeVisibleKey,
  computed(() => open.value),
)
let cursor = props.item.nextHistoryCursor ?? undefined
let controller: globalThis.AbortController | undefined,
  generation = 0
let previousOverflow: string | undefined

function cancel() {
  generation++
  controller?.abort()
  loading.value = false
}
function unlockScroll() {
  if (previousOverflow !== undefined) {
    globalThis.document.documentElement.style.overflow = previousOverflow
    previousOverflow = undefined
  }
}
/** 原生模态对话框负责焦点约束和背景inert，正文滚动在面板内部完成。 */
function show() {
  if (open.value) return
  open.value = true
  previousOverflow = globalThis.document.documentElement.style.overflow
  globalThis.document.documentElement.style.overflow = 'hidden'
  dialog.value?.showModal()
  if (!loaded.value) void load()
}
function close() {
  open.value = false
  back.value?.()
  cancel()
  dialog.value?.close()
  unlockScroll()
  if (props.restoreFocus) props.restoreFocus()
  else trigger.value?.focus({ preventScroll: true })
}
/** close也处理浏览器或外部代码关闭对话框的情况，清理必须可重复执行。 */
function closed() {
  if (dialog.value?.open) return
  open.value = false
  cancel()
  unlockScroll()
}
onUnmounted(() => {
  cancel()
  unlockScroll()
})

/** 每段核验当前代表；代表改变先关闭面板，再刷新主列表，避免双重展示。 */
async function load() {
  if (loading.value || !gateway.history || !props.item.authorId) return
  const ticket = ++generation
  controller = new globalThis.AbortController()
  loading.value = true
  error.value = ''
  try {
    const page = await gateway.history(
      props.restaurantId,
      props.item.authorId,
      controller.signal,
      cursor,
    )
    if (ticket !== generation) return
    if (page.currentReviewId !== props.item.id) {
      records.value = []
      close()
      emit('refresh')
      return
    }
    const known = new Set(records.value.map((r) => r.id))
    records.value.push(...page.records.filter((r) => !known.has(r.id)))
    cursor = page.nextCursor ?? undefined
    hasMore.value = page.hasMore
    loaded.value = true
  } catch (reason) {
    if (ticket === generation)
      error.value = reason instanceof Error ? reason.message : '历史加载失败，请重试。'
  } finally {
    if (ticket === generation) loading.value = false
  }
}
function refresh() {
  close()
  emit('refresh')
}
</script>

<template>
  <button
    ref="trigger"
    type="button"
    class="history-trigger"
    aria-haspopup="dialog"
    :aria-expanded="open"
    @click="show"
  >
    <svg class="history-trigger-icon" viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 2" />
    </svg>
    历史点评 <span>{{ item.historyCount }}</span>
    <span class="history-trigger-chevron" aria-hidden="true">›</span>
  </button>
  <Teleport to="body">
    <dialog
      ref="dialog"
      class="history-dialog"
      :aria-labelledby="activeId ? undefined : titleId"
      :aria-label="activeId ? storeName || '历史点评' : undefined"
      @cancel.prevent="activeId && back ? back() : close()"
      @close="closed"
      @click.self="close"
    >
      <div class="history-sheet">
        <header v-show="!activeId" class="history-header">
          <span class="sheet-grip" aria-hidden="true" />
          <div class="history-heading">
            <div>
              <p class="history-author">{{ item.username }}</p>
              <h2 :id="titleId">
                {{ desktop && storeName ? storeName : '历史点评' }}
                <span v-if="!desktop || !storeName">{{ item.historyCount }}</span>
              </h2>
              <p v-if="desktop && storeName" class="history-count">
                历史点评 · {{ item.historyCount }} 条
              </p>
            </div>
            <button
              type="button"
              class="close-history"
              aria-label="关闭历史点评"
              autofocus
              @click="close"
            >
              ×
            </button>
          </div>
        </header>
        <div v-show="!activeId" class="history-scroll">
          <ReviewEntry
            v-for="record in records"
            :key="record.id"
            :item="record"
            detail-entry
            :store-to="`/restaurants/${restaurantId}`"
            :store-name="storeName || '查看店铺'"
            :star-scale="starScale"
            :authenticated="!!viewerId"
            :viewer-id="viewerId"
            :busy="false"
            :reply-texts="{}"
            :show-history="false"
            :replies-enabled="gateway.supportsReplies === true && !!gateway.listReplies"
            @photo="emit('photo', $event)"
            @delete="
              (record) => {
                close()
                emit('delete', record)
              }
            "
          />
          <p v-if="loading" role="status" class="history-status">正在加载历史点评…</p>
          <div v-else-if="error" role="alert" class="history-error">
            <p>{{ error }}</p>
            <button type="button" class="history-more" @click="load">重试历史点评</button>
            <button type="button" class="history-more" @click="refresh">重新加载点评列表</button>
          </div>
          <button v-else-if="loaded && hasMore" type="button" class="history-more" @click="load">
            加载更多历史点评
          </button>
          <p v-else-if="loaded" class="history-status">已显示全部历史点评</p>
        </div>
        <div v-show="activeId" ref="detailHost" class="history-detail-host" />
      </div>
    </dialog>
  </Teleport>
</template>

<style scoped>
.history-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 0;
  background: transparent;
  color: #85919f;
  font: inherit;
  font-size: 13px;
  min-height: 44px;
  padding: 8px 0;
  cursor: pointer;
}
.history-trigger span {
  color: #929dab;
  font-size: 12px;
}
.history-trigger-icon,
.history-trigger-chevron {
  display: none;
}
.history-trigger:hover {
  color: #344252;
}
.history-dialog {
  position: fixed;
  inset: 0 0 0 auto;
  width: min(560px, 100vw);
  height: 100vh;
  height: 100dvh;
  max-height: 100vh;
  max-height: 100dvh;
  max-width: 100vw;
  margin: 0;
  padding: 0;
  border: 0;
  border-radius: 20px 0 0 20px;
  background: #fff;
  color: #344252;
  box-shadow: -12px 0 60px #24314214;
  font-family: 'Microsoft YaHei', 'PingFang SC', sans-serif;
  overflow: hidden;
  box-sizing: border-box;
}
.history-dialog::backdrop {
  background: #24314245;
}
.history-sheet {
  height: 100%;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-width: 0;
}
.history-header {
  padding: 28px 24px 16px;
  border-bottom: 1px solid #edf0f3;
}
.history-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.history-heading > div {
  min-width: 0;
}
.history-author {
  margin: 0 0 6px;
  color: #85919f;
  font-size: 12px;
  overflow-wrap: anywhere;
}
h2 {
  font-size: 20px;
  font-weight: 600;
  margin: 0;
}
h2 span {
  color: #929dab;
  font-size: 14px;
  margin-left: 6px;
  font-weight: 400;
}
.close-history {
  flex-shrink: 0;
  border: 0;
  border-radius: 50%;
  background: #f5f6f8;
  color: #85919f;
  font-size: 25px;
  width: 40px;
  height: 40px;
  cursor: pointer;
}
.history-scroll {
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 0 24px 24px;
  min-height: 0;
}
.history-scroll :deep(.review-card) {
  margin-top: 0;
  padding: 24px 0;
}
.history-scroll :deep(.review-card:first-child) {
  border-top: 0;
}
.history-status {
  text-align: center;
  color: #929dab;
  font-size: 12px;
  padding: 16px 0;
}
.history-more {
  border: 0;
  background: transparent;
  color: #344252;
  font-size: 13px;
  min-height: 44px;
  padding: 8px 12px;
  cursor: pointer;
}
.history-error {
  font-size: 13px;
  color: #bf4e48;
  overflow-wrap: anywhere;
}
.sheet-grip {
  display: none;
}
.history-detail-host {
  height: 100%;
  min-height: 0;
  grid-row: 1 / -1;
}
.history-count {
  margin: 6px 0 0;
  color: #9aa1a8;
  font-size: 12px;
}
@media (min-width: 800px) {
  .history-trigger-icon {
    display: block;
    width: 15px;
    height: 15px;
    fill: none;
    stroke: currentColor;
    stroke-width: 1.5;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
  .history-trigger-chevron {
    display: inline;
  }
  .history-dialog {
    inset: 0;
    width: min(720px, calc(100vw - 64px));
    height: min(688px, calc(100vh - 64px));
    height: min(688px, calc(100dvh - 64px));
    margin: auto;
    border-radius: 14px;
    box-shadow: 0 22px 100px #18263826;
  }
  .history-header {
    padding: 23px 30px 19px;
  }
  .history-author {
    display: none;
  }
  h2 {
    font-size: 21px;
  }
  .close-history {
    background: transparent;
  }
  .history-scroll {
    padding: 0 30px 24px;
  }
  .history-scroll :deep(.review-card) {
    --entry-indent: 0px;
    padding: 22px 0;
  }
  .history-scroll :deep(.entry-author),
  .history-scroll :deep(.dimension-details),
  .history-scroll :deep(.discussion-preview),
  .history-scroll :deep(.discussion-preview-loading),
  .history-scroll :deep(.discussion-preview-error),
  .history-scroll :deep(.delete-review) {
    display: none;
  }
  .history-scroll :deep(.entry-rating),
  .history-scroll :deep(.entry-date) {
    grid-row: 1;
    margin: 0;
  }
  .history-scroll :deep(.body) {
    font-size: 14px;
    margin-top: 12px;
    margin-bottom: 8px;
  }
  .history-scroll :deep(.entry-interactions) {
    grid-template-columns: auto minmax(0, 1fr) auto;
    gap: 0 20px;
    margin-top: 8px;
  }
  .history-scroll :deep(.history-detail-link) {
    display: block;
    grid-column: 3;
    grid-row: 1;
    font-size: 12px;
  }
  .history-scroll :deep(.content-actions) {
    margin: 0;
    align-self: center;
  }
}
button:focus-visible {
  outline: 2px solid #a5adb7;
  outline-offset: 3px;
}
@media (max-width: 640px) {
  .history-dialog {
    inset: auto 0 0;
    width: 100%;
    height: 88vh;
    height: 88dvh;
    max-height: 88vh;
    max-height: 88dvh;
    border-radius: 20px 20px 0 0;
  }
  .history-header {
    padding: 12px 18px 14px;
  }
  .history-scroll {
    padding: 0 18px max(20px, env(safe-area-inset-bottom));
  }
  .sheet-grip {
    display: block;
    width: 32px;
    height: 4px;
    border-radius: 4px;
    background: #dce1e7;
    margin: 0 auto 16px;
  }
}
</style>
