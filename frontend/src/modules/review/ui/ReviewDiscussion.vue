<script setup lang="ts">
import { computed, inject, nextTick, onMounted, onUnmounted, provide, ref, useId, watch } from 'vue'
import { useRoute } from 'vue-router'
import { authToken } from '@/shared/api/authSession'
import { replyScopeVisibleKey } from '../model/replyEditor'
import { reviewGatewayKey, unavailableReviewGateway, type ReplyPage } from '../model/review'
import ReplyThread from './ReplyThread.vue'

/** 本人卡片的两条回复预览与独立详情；关闭不卸载编辑器，草稿和失败UUID保留在原组件。 */
const props = defineProps<{
  reviewId: string
  username: string
  /** 消息入口直接打开同一面板，不再渲染列表中的回复预览。 */
  standalone?: boolean
  /** 目标读取成功后才挂载回复编辑器，错误时仅允许重试和关闭。 */
  pending?: boolean
  focusedReply?: ReplyPage['records'][number]
}>()
const emit = defineEmits<{ close: [] }>()
const gateway = inject(reviewGatewayKey, unavailableReviewGateway)
const parentVisible = inject(replyScopeVisibleKey, ref(true))
const opened = ref(false),
  initialized = ref(false)
const visible = computed(() => opened.value && parentVisible.value)
provide(replyScopeVisibleKey, visible)
const page = ref<ReplyPage>()
const loading = ref(false),
  error = ref('')
const dialog = ref<globalThis.HTMLDialogElement>(),
  scroll = ref<globalThis.HTMLDivElement>()
const editor = ref<InstanceType<typeof ReplyThread>>()
const trigger = ref<globalThis.HTMLButtonElement>()
const titleId = 'discussion-title-' + useId()
const route = useRoute()
let controller: globalThis.AbortController | undefined,
  generation = 0
let previousOverflow: string | undefined
let returnFocus: globalThis.HTMLElement | undefined
let scrollTop = 0

/** 无总数的游标接口只在全部读完时显示确切数量，不能把首批条数当成总数。 */
const previewLabel = computed(() =>
  page.value?.hasMore ? '查看全部回复' : `共${page.value?.records.length ?? 0}条回复`,
)
function cancelPreview() {
  generation++
  controller?.abort()
  loading.value = false
}
async function loadPreview() {
  if (props.standalone || !gateway.listReplies || loading.value || !parentVisible.value) return
  const ticket = ++generation
  controller = new globalThis.AbortController()
  loading.value = true
  error.value = ''
  try {
    const result = await gateway.listReplies(props.reviewId, controller.signal)
    if (ticket === generation) page.value = result
  } catch (reason) {
    if (ticket === generation)
      error.value = reason instanceof Error ? reason.message : '回复加载失败。'
  } finally {
    if (ticket === generation) loading.value = false
  }
}
function updatePage(value: ReplyPage) {
  page.value = value
  error.value = ''
}
function invalidate() {
  page.value = undefined
  if (!opened.value) void loadPreview()
}
function unlock() {
  if (previousOverflow !== undefined) {
    globalThis.document.documentElement.style.overflow = previousOverflow
    previousOverflow = undefined
  }
}
async function show(event?: globalThis.MouseEvent) {
  if (opened.value) return
  returnFocus =
    event?.currentTarget instanceof globalThis.HTMLElement ? event.currentTarget : trigger.value
  cancelPreview()
  initialized.value = true
  opened.value = true
  previousOverflow = globalThis.document.documentElement.style.overflow
  globalThis.document.documentElement.style.overflow = 'hidden'
  dialog.value?.showModal()
  await nextTick()
  if (scroll.value) scroll.value.scrollTop = scrollTop
}
/** 在途发布可正常完成但不能重开已关闭面板；关闭只取消读取，不抛弃失败请求号。 */
function close() {
  if (!opened.value) return
  scrollTop = scroll.value?.scrollTop ?? 0
  opened.value = false
  dialog.value?.close()
  unlock()
  if (returnFocus?.isConnected) returnFocus.focus({ preventScroll: true })
  emit('close')
}
function closed() {
  if (opened.value) close()
  unlock()
}
/** 手机键盘可能只缩小可视视口；让详情底栏跟随可见区域，避免发送按钮被键盘盖住。 */
function fitViewport() {
  const viewport = globalThis.visualViewport
  if (!opened.value || !dialog.value) return
  if (viewport && globalThis.innerWidth < 800 && viewport.scale === 1) {
    dialog.value.style.height = `${viewport.height}px`
    dialog.value.style.top = `${viewport.offsetTop}px`
  } else {
    dialog.value.style.removeProperty('height')
    dialog.value.style.removeProperty('top')
  }
}
function stopViewport() {
  globalThis.visualViewport?.removeEventListener('resize', fitViewport)
  globalThis.visualViewport?.removeEventListener('scroll', fitViewport)
  globalThis.removeEventListener('resize', fitViewport)
}
watch(opened, (value) => {
  if (value) {
    globalThis.visualViewport?.addEventListener('resize', fitViewport)
    globalThis.visualViewport?.addEventListener('scroll', fitViewport)
    globalThis.addEventListener('resize', fitViewport)
    fitViewport()
  } else {
    stopViewport()
    dialog.value?.style.removeProperty('height')
    dialog.value?.style.removeProperty('top')
  }
})
watch(
  authToken,
  () => {
    close()
    cancelPreview()
    page.value = undefined
    error.value = ''
    // 新身份不继承旧账号编辑器；在途回执由ReplyThread卸载时的上下文校验隔离。
    initialized.value = false
    scrollTop = 0
    void loadPreview()
  },
  { flush: 'sync' },
)
watch(
  () => props.reviewId,
  () => {
    close()
    cancelPreview()
    initialized.value = false
    page.value = undefined
    scrollTop = 0
    void loadPreview()
  },
)
watch(() => route.fullPath, close)
watch(parentVisible, (value) => {
  if (!value) {
    close()
    cancelPreview()
  } else if (!page.value) void loadPreview()
})
onMounted(() => {
  if (props.standalone) void show()
  else void loadPreview()
})
onUnmounted(() => {
  cancelPreview()
  stopViewport()
  opened.value = false
  dialog.value?.close()
  unlock()
})
// 正文和回复入口共用一个详情实例，保留草稿、失败请求号及当前详情阅读位置。
defineExpose({ show })
</script>

<template>
  <div class="reply-thread discussion-thread">
    <div v-if="!standalone" class="thread-actions">
      <button
        ref="trigger"
        type="button"
        class="discussion-reply-action"
        aria-haspopup="dialog"
        :aria-expanded="opened"
        @click="show"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M21 11.5a8.5 8.5 0 0 1-12.7 7.4L3 21l1.8-5.4A8.5 8.5 0 1 1 21 11.5Z" /></svg
        >回复
      </button>
    </div>
    <button
      v-if="!standalone && page?.records.length"
      type="button"
      class="discussion-preview"
      :aria-label="previewLabel"
      @click="show"
    >
      <span v-for="answer in page.records.slice(0, 2)" :key="answer.id" class="preview-line"
        ><span class="preview-author">{{ answer.author.username }}：</span>{{ answer.body }}</span
      >
      <span class="preview-link"
        >{{ previewLabel
        }}<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 5 7 7-7 7" /></svg
      ></span>
    </button>
    <button
      v-else-if="!standalone && error"
      type="button"
      class="discussion-preview-error"
      @click="loadPreview"
    >
      回复加载失败，点击重试
    </button>
    <span v-else-if="!standalone && loading" class="discussion-preview-loading" role="status"
      >正在加载回复…</span
    >
    <Teleport to="body">
      <dialog
        ref="dialog"
        class="discussion-dialog"
        :aria-labelledby="titleId"
        @cancel.prevent="close"
        @close="closed"
        @click="$event.target === dialog && close()"
      >
        <header class="discussion-header">
          <button
            type="button"
            :aria-label="standalone ? '返回消息列表' : '返回点评列表'"
            class="discussion-back"
            autofocus
            @click="close"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 5-7 7 7 7" /></svg>
          </button>
          <h2 :id="titleId">点评详情</h2>
          <button type="button" aria-label="关闭点评详情" @click="close">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M6 18 18 6" /></svg>
          </button>
        </header>
        <div ref="scroll" class="discussion-scroll">
          <slot v-if="initialized" name="original" :focus-input="() => editor?.focusInput()" />
          <ReplyThread
            v-if="initialized && !pending"
            ref="editor"
            :key="reviewId"
            :review-id="reviewId"
            :username="username"
            detail-mode
            :initial-page="page"
            :focused-reply="focusedReply"
            @page="updatePage"
            @invalidate="invalidate"
          />
        </div>
      </dialog>
    </Teleport>
  </div>
</template>

<style scoped src="./review-discussion.css"></style>
