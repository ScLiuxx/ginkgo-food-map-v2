<script setup lang="ts">
import { computed, inject, onUnmounted, provide, ref, useId } from 'vue'
import { reviewGatewayKey, unavailableReviewGateway } from '../model/review'
import { replyScopeVisibleKey } from '../model/replyEditor'
import type { ReviewItem } from '../model/review'
import ReviewEntry from './ReviewEntry.vue'
import { windowReviewGateway } from '../api/reviewApi'

/** 历史只在独立面板内展示；关闭保留记录和回复草稿，不把旧卡片嵌回主列表。 */
const props = defineProps<{
  window?: boolean
  restaurantId: string
  item: ReviewItem
  viewerId?: string
  starScale?: boolean
}>()
const emit = defineEmits<{
  photo: [url: string]
  refresh: []
  delete: [item: ReviewItem]
}>()
const gateway = props.window
  ? windowReviewGateway
  : inject(reviewGatewayKey, unavailableReviewGateway)
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
  cancel()
  dialog.value?.close()
  unlockScroll()
  trigger.value?.focus({ preventScroll: true })
}
/** close也处理浏览器或外部代码关闭对话框的情况，清理必须可重复执行。 */
function closed() {
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
    历史点评 <span>{{ item.historyCount }}</span>
  </button>
  <Teleport to="body">
    <dialog
      ref="dialog"
      class="history-dialog"
      :aria-labelledby="titleId"
      @cancel.prevent="close"
      @close="closed"
      @click.self="close"
    >
      <div class="history-sheet">
        <header class="history-header">
          <span class="sheet-grip" aria-hidden="true" />
          <div class="history-heading">
            <div>
              <p class="history-author">{{ item.username }}</p>
              <h2 :id="titleId">
                历史点评 <span>{{ item.historyCount }}</span>
              </h2>
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
          <p class="history-description">同一家店的过往记录，保留原有照片与回复。</p>
        </header>
        <div class="history-scroll">
          <ReviewEntry
            v-for="record in records"
            :key="record.id"
            :item="record"
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
            <button type="button" class="history-more" @click="refresh">刷新点评列表</button>
          </div>
          <button v-else-if="loaded && hasMore" type="button" class="history-more" @click="load">
            加载更多历史点评
          </button>
          <p v-else-if="loaded" class="history-status">已显示全部历史点评</p>
        </div>
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
  color: #938371;
  font: inherit;
  font-size: 13px;
  min-height: 44px;
  padding: 8px 0;
  cursor: pointer;
}
.history-trigger span {
  color: #b0a28e;
  font-size: 12px;
}
.history-trigger:hover {
  color: #674714;
}
.history-dialog {
  position: fixed;
  inset: 0 0 0 auto;
  width: min(560px, 100vw);
  height: 100dvh;
  max-height: 100dvh;
  max-width: 100vw;
  margin: 0;
  padding: 0;
  border: 0;
  border-radius: 20px 0 0 20px;
  background: #fffefa;
  color: #423d35;
  box-shadow: -12px 0 60px #30271820;
  overflow: hidden;
  box-sizing: border-box;
}
.history-dialog::backdrop {
  background: #29241c66;
}
.history-sheet {
  height: 100%;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-width: 0;
}
.history-header {
  padding: 28px 24px 16px;
  border-bottom: 1px solid #eee7dc;
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
  color: #9b8a71;
  font-size: 12px;
  overflow-wrap: anywhere;
}
h2 {
  font-size: 20px;
  font-weight: 600;
  margin: 0;
}
h2 span {
  color: #aa977d;
  font-size: 14px;
  margin-left: 6px;
  font-weight: 400;
}
.close-history {
  flex-shrink: 0;
  border: 0;
  border-radius: 50%;
  background: #f4f0e8;
  color: #8b7b63;
  font-size: 25px;
  width: 40px;
  height: 40px;
  cursor: pointer;
}
.history-description {
  font-size: 12px;
  color: #9a8d7a;
  line-height: 1.7;
  margin: 12px 0 0;
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
  color: #aa9d88;
  font-size: 12px;
  padding: 16px 0;
}
.history-more {
  border: 0;
  background: transparent;
  color: #8c734c;
  font-size: 13px;
  min-height: 44px;
  padding: 8px 12px;
  cursor: pointer;
}
.history-error {
  font-size: 13px;
  color: #a53c2e;
  overflow-wrap: anywhere;
}
.sheet-grip {
  display: none;
}
button:focus-visible {
  outline: 2px solid #ad7618;
  outline-offset: 3px;
}
@media (max-width: 640px) {
  .history-dialog {
    inset: auto 0 0;
    width: 100%;
    height: 88dvh;
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
    background: #ddd3c3;
    margin: 0 auto 16px;
  }
}
</style>
