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
const time = (value: string) =>
  new Date(value).toLocaleString('zh-CN', {
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
</script>
<template>
  <Teleport to="body">
    <dialog
      ref="dialog"
      class="reply-notification-dialog content-dialog"
      aria-labelledby="reply-target-heading"
      @cancel.prevent="close"
      @close="close"
    >
      <header class="detail-dialog-header">
        <h2 id="reply-target-heading">点评与回复</h2>
        <button type="button" class="detail-dialog-close" aria-label="关闭评论详情" @click="close">
          ×
        </button>
      </header>
      <p v-if="loading" class="detail-dialog-state" role="status">正在定位点评和回复…</p>
      <div v-else-if="error" class="detail-dialog-state" role="alert">
        <p>{{ error }}</p>
        <button type="button" class="dialog-secondary" @click="load">重试加载</button>
      </div>
      <template v-else-if="target">
        <!-- 原点评保留上下文并适度弱化，让本次收到的回复成为主要阅读内容。 -->
        <article class="reply-target-review">
          <h3>原点评</h3>
          <div class="review-identity">
            <strong>{{ target.reviewAuthor.username }}</strong>
            <span class="review-score"
              >★ {{ (target.review.overallScore / 2).toFixed(1) }} ·
              {{ target.review.recommended ? '推荐' : '不推荐' }}</span
            >
          </div>
          <p>{{ target.review.body }}</p>
          <time class="detail-dialog-date" :datetime="target.review.createdAt">{{
            time(target.review.createdAt)
          }}</time>
        </article>
        <article class="reply-target-highlight">
          <div class="reply-identity">
            <strong class="detail-dialog-author">{{ target.replyAuthor.username }}</strong>
            <span>回复</span>
            <time class="detail-dialog-date" :datetime="target.replyCreatedAt">{{
              time(target.replyCreatedAt)
            }}</time>
          </div>
          <p class="detail-dialog-body">{{ target.replyBody }}</p>
        </article>
      </template>
      <footer class="detail-dialog-actions">
        <button type="button" class="dialog-secondary" @click="close">关闭</button>
        <RouterLink
          v-if="target"
          class="dialog-primary"
          :to="`/${target.windowId ? 'canteen-windows' : 'restaurants'}/${target.windowId ?? target.restaurantId}`"
          @click="close"
          >进入店铺 →</RouterLink
        >
      </footer>
    </dialog>
  </Teleport>
</template>
<style scoped src="../../../shared/styles/content-dialog.css"></style>
<style scoped>
.reply-target-review {
  margin-top: 22px;
  padding: 16px 18px;
  border: 1px solid #eef0f3;
  border-radius: 9px;
  background: #f7f8fa;
}
.reply-target-review h3 {
  margin: 0 0 12px;
  color: #939dab;
  font-size: 11px;
  font-weight: 400;
}
.review-identity {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 6px 10px;
}
.review-identity strong {
  color: #374453;
  font-size: 14px;
  font-weight: 650;
}
.review-score {
  color: #ed862b;
  font-size: 12px;
}
.reply-target-review p {
  margin: 10px 0;
  color: #687584;
  white-space: pre-wrap;
  font-size: 13px;
  line-height: 1.8;
}
.reply-target-highlight {
  margin-top: 24px;
}
.reply-identity {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 6px 9px;
}
.reply-identity > span {
  color: #9ca5af;
  font-size: 11px;
}
.reply-identity time {
  margin-left: auto;
}
</style>
