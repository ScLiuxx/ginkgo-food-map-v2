<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import axios from 'axios'
import { useLoginStore } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { fetchReplyTarget, type ReplyTarget } from '../api/replyTargetApi'

/** 精确定位弹窗独立读取原点评及目标回复，关闭、换号后销毁正文并拒绝迟到响应。 */
const props = defineProps<{ replyId: string; reviewId: string }>()
const emit = defineEmits<{ close: [] }>()
const auth = useLoginStore(),
  dialog = ref<globalThis.HTMLDialogElement>(),
  target = ref<ReplyTarget | null>(null),
  loading = ref(false),
  error = ref('')
let version = 0,
  controller: globalThis.AbortController | undefined
function clear() {
  version++
  controller?.abort()
  target.value = null
  loading.value = false
  error.value = ''
}
function close() {
  clear()
  emit('close')
}
async function load() {
  clear()
  const token = authToken.value,
    userId = auth.authenticated && auth.user ? String(auth.user.id) : null
  if (!token || !userId) return
  const ticket = version
  controller = new globalThis.AbortController()
  loading.value = true
  try {
    const result = await fetchReplyTarget(
      props.replyId,
      props.reviewId,
      userId,
      token,
      controller.signal,
    )
    if (ticket === version) target.value = result
  } catch (reason) {
    if (ticket === version)
      error.value =
        axios.isAxiosError(reason) && reason.response?.status === 404
          ? '内容已失效或不可访问。'
          : toErrorMessage(reason)
  } finally {
    if (ticket === version) loading.value = false
  }
}
watch(
  () => [props.replyId, props.reviewId],
  () => {
    void load()
  },
  { immediate: true },
)
watch(() => [auth.user?.id, authToken.value], close, { flush: 'sync' })
watch(
  dialog,
  (value) => {
    if (value && !value.open) value.showModal()
  },
  { flush: 'post' },
)
onBeforeUnmount(clear)
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
</script>
<template>
  <Teleport to="body"
    ><dialog
      ref="dialog"
      class="reply-notification-dialog"
      aria-labelledby="reply-target-heading"
      @cancel.prevent="close"
      @close="close"
    >
      <header>
        <h2 id="reply-target-heading">点评与回复</h2>
        <button type="button" aria-label="关闭评论详情" @click="close">关闭</button>
      </header>
      <p v-if="loading" role="status">正在定位点评和回复…</p>
      <div v-else-if="error" role="alert">
        <p>{{ error }}</p>
        <button type="button" @click="load">重试加载</button>
      </div>
      <template v-else-if="target">
        <article class="reply-target-review">
          <h3>原点评</h3>
          <strong>{{ target.reviewAuthor.username }}</strong
          ><span>
            · {{ target.review.overallScore.toFixed(2) }} 分 ·
            {{ target.review.recommended ? '推荐' : '不推荐' }}</span
          >
          <p>{{ target.review.body }}</p>
          <time>{{ time(target.review.createdAt) }}</time>
        </article>
        <article class="reply-target-highlight">
          <h3>本条回复</h3>
          <strong>{{ target.replyAuthor.username }}</strong>
          <p>{{ target.replyBody }}</p>
          <time>{{ time(target.replyCreatedAt) }}</time>
        </article>
        <RouterLink
          :to="`/${target.windowId ? 'canteen-windows' : 'restaurants'}/${target.windowId ?? target.restaurantId}`"
          @click="close"
          >进入店铺 ›</RouterLink
        >
      </template>
    </dialog></Teleport
  >
</template>
<style scoped>
.reply-notification-dialog {
  width: min(640px, calc(100vw - 32px));
  max-height: 85vh;
  box-sizing: border-box;
  overflow: auto;
  border: 1px solid #eee7db;
  border-radius: 14px;
  padding: 24px;
  color: #27313c;
}
.reply-notification-dialog::backdrop {
  background: #0006;
}
.reply-notification-dialog header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.reply-notification-dialog h2 {
  font-size: 19px;
  margin: 0;
}
.reply-notification-dialog h3 {
  font-size: 14px;
  color: #86653a;
}
.reply-notification-dialog article {
  padding: 12px 0;
  overflow-wrap: anywhere;
}
.reply-notification-dialog p {
  white-space: pre-wrap;
  line-height: 1.8;
}
.reply-target-highlight {
  background: #fff8e9;
  padding: 12px !important;
  border-radius: 8px;
  margin: 12px 0;
}
.reply-notification-dialog time {
  font-size: 12px;
  color: #737a80;
}
.reply-notification-dialog button {
  padding: 6px 10px;
  background: white;
  border: 1px solid #ddd;
  border-radius: 6px;
  cursor: pointer;
}
.reply-notification-dialog a {
  color: #a36524;
}
</style>
