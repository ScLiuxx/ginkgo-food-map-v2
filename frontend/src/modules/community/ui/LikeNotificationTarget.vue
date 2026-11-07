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
  restaurantId: string
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
        { signal: controller.signal, headers: { Authorization: `Bearer ${authToken.value}` } },
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
      !notificationId(data.restaurantId) ||
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
  <Teleport to="body"
    ><dialog
      ref="dialog"
      class="like-target-dialog"
      aria-labelledby="like-target-title"
      @cancel.prevent="close"
      @close="close"
    >
      <header>
        <h2 id="like-target-title">获赞{{ type === 'REVIEW' ? '点评' : '回复' }}</h2>
        <button @click="close">关闭获赞详情</button>
      </header>
      <p v-if="loading" role="status">正在读取获赞内容…</p>
      <div v-else-if="error" role="alert">
        <p>{{ error }}</p>
        <button @click="load">重试加载</button>
      </div>
      <article v-else-if="target">
        <strong>{{ target.author.username }}</strong>
        <p>{{ target.body }}</p>
        <time>{{ new Date(target.createdAt).toLocaleString('zh-CN') }}</time>
        <p>
          <RouterLink :to="`/restaurants/${target.restaurantId}`" @click="close"
            >进入店铺 ›</RouterLink
          >
        </p>
      </article>
    </dialog></Teleport
  >
</template>
<style scoped>
.like-target-dialog {
  width: min(640px, calc(100vw - 32px));
  max-height: 85vh;
  box-sizing: border-box;
  overflow: auto;
  border: 1px solid #eee7db;
  border-radius: 14px;
  padding: 24px;
  color: #27313c;
  overflow-wrap: anywhere;
}
.like-target-dialog::backdrop {
  background: #0006;
}
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
h2 {
  font-size: 19px;
}
p {
  white-space: pre-wrap;
  line-height: 1.8;
}
time {
  font-size: 12px;
  color: #737a80;
}
button {
  padding: 6px 10px;
  background: white;
  border: 1px solid #ddd;
  border-radius: 6px;
  cursor: pointer;
}
</style>
