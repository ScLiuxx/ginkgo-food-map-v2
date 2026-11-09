<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import axios from 'axios'
import { useLoginStore } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'
import { httpClient } from '@/shared/api/httpClient'
import type { ApiResponse } from '@/shared/api/api.types'
import { notificationId } from '@/modules/message/api/notificationsApi'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
interface Target {
  userId: string
  likeId: string
  targetType: 'REVIEW' | 'REPLY'
  targetId: string
  reviewId: string
  restaurantId: string | null
  windowId?: string | null
  author: { id: string; username: string }
  body: string
  createdAt: string
}
/** 获赞对象属于点评作者或回复作者，独立鉴权；关闭或换号销毁正文并拒绝迟到回执。 */
const props = defineProps<{ id: string; type: 'REVIEW' | 'REPLY' }>(),
  emit = defineEmits<{ close: [] }>(),
  auth = useLoginStore()
const dialog = ref<globalThis.HTMLDialogElement>(),
  target = ref<Target | null>(null),
  error = ref(''),
  loading = ref(false)
let version = 0,
  controller: globalThis.AbortController | undefined
function clear() {
  version++
  controller?.abort()
  target.value = null
  error.value = ''
  loading.value = false
}
function close() {
  clear()
  emit('close')
}
async function load() {
  clear()
  if (!auth.authenticated || !authToken.value) return
  const ticket = version,
    user = String(auth.user?.id)
  controller = new globalThis.AbortController()
  loading.value = true
  try {
    const response = await httpClient.get<ApiResponse<Target>>(
        `/api/me/received-likes/${props.id}/target`,
        {
          signal: controller.signal,
          headers: { Authorization: `Bearer ${authToken.value}` },
        },
      ),
      data = response.data.data
    if (
      !response.data.success ||
      !data ||
      data.userId !== user ||
      data.likeId !== props.id ||
      data.targetType !== props.type ||
      !notificationId(data.targetId) ||
      !notificationId(data.reviewId) ||
      !(
        (notificationId(data.restaurantId) && !data.windowId) ||
        (data.restaurantId === null && notificationId(data.windowId))
      ) ||
      data.author?.id !== user ||
      typeof data.author.username !== 'string' ||
      typeof data.body !== 'string' ||
      !Number.isFinite(Date.parse(data.createdAt))
    )
      throw new Error('获赞内容无法确认，请重新加载。')
    if (ticket === version)
      target.value = {
        userId: user,
        likeId: props.id,
        targetType: data.targetType,
        targetId: data.targetId,
        reviewId: data.reviewId,
        restaurantId: data.restaurantId,
        windowId: data.windowId,
        author: { id: data.author.id, username: data.author.username },
        body: data.body,
        createdAt: data.createdAt,
      }
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
  () => [props.id, props.type],
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
</script>
<template>
  <Teleport to="body">
    <dialog
      ref="dialog"
      class="like-target-dialog content-dialog"
      aria-labelledby="like-target-title"
      @cancel.prevent="close"
      @close="close"
    >
      <header class="detail-dialog-header">
        <h2 id="like-target-title">获赞{{ type === 'REVIEW' ? '点评' : '回复' }}</h2>
        <button type="button" class="detail-dialog-close" aria-label="关闭获赞详情" @click="close">
          ×
        </button>
      </header>
      <p v-if="loading" class="detail-dialog-state" role="status">正在读取获赞内容…</p>
      <div v-else-if="error" class="detail-dialog-state" role="alert">
        <p>{{ error }}</p>
        <button type="button" class="dialog-secondary" @click="load">重试加载</button>
      </div>
      <article v-else-if="target" class="like-target-content">
        <strong class="detail-dialog-author">{{ target.author.username }}</strong>
        <time class="detail-dialog-date" :datetime="target.createdAt">{{
          new Date(target.createdAt).toLocaleString('zh-CN', {
            year: 'numeric',
            month: 'numeric',
            day: 'numeric',
            hour: '2-digit',
            minute: '2-digit',
            hour12: false,
          })
        }}</time>
        <p class="detail-dialog-body">{{ target.body }}</p>
      </article>
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
.like-target-content {
  padding-top: 22px;
}
.like-target-content time {
  margin-top: 8px;
}
</style>
