<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onUnmounted, ref, watch } from 'vue'
import { isAxiosError } from 'axios'
import { useLoginStore } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import {
  fetchNotificationReview,
  type ReviewDetail,
  type ReviewNotificationType,
} from '../api/reviewDetailApi'
import ReviewDiscussion from './ReviewDiscussion.vue'
import ReviewDetailOriginal from './ReviewDetailOriginal.vue'

/** 消息和个人点评共用同一详情与回复编辑器；关闭留在来源列表，不为消息跳转店铺。 */
const props = defineProps<{
  reviewId: string
  sourceId: string
  type: ReviewNotificationType
  notificationId?: string
}>()
const emit = defineEmits<{ close: [] }>()
const auth = useLoginStore()
const detail = ref<ReviewDetail | null>(null),
  loading = ref(false),
  error = ref('')
const photo = ref(''),
  lightbox = ref<globalThis.HTMLDialogElement>()
const returnFocus =
  globalThis.document.activeElement instanceof globalThis.HTMLElement
    ? globalThis.document.activeElement
    : undefined
const storeTo = computed(() =>
  detail.value
    ? `/${detail.value.windowId ? 'canteen-windows' : 'restaurants'}/${detail.value.windowId ?? detail.value.restaurantId}`
    : undefined,
)
let generation = 0,
  controller: globalThis.AbortController | undefined
async function load() {
  controller?.abort()
  const ticket = ++generation,
    token = authToken.value
  const userId = auth.authenticated && auth.user ? String(auth.user.id) : null
  detail.value = null
  error.value = ''
  if (!token || !userId) {
    emit('close')
    return
  }
  controller = new globalThis.AbortController()
  loading.value = true
  try {
    const result = await fetchNotificationReview(
      props.type,
      props.sourceId,
      props.reviewId,
      userId,
      token,
      controller.signal,
    )
    if (ticket === generation && token === authToken.value && String(auth.user?.id) === userId)
      detail.value = result
  } catch (reason) {
    if (ticket === generation)
      error.value =
        isAxiosError(reason) && reason.response?.status === 404
          ? '对应点评或回复已删除，或暂时不可访问。'
          : toErrorMessage(reason)
  } finally {
    if (ticket === generation) loading.value = false
  }
}
async function viewPhoto(url: string) {
  photo.value = url
  await nextTick()
  lightbox.value?.showModal()
}
watch(
  () => [props.reviewId, props.sourceId, props.type],
  () => void load(),
  { immediate: true },
)
watch(
  () => [auth.user?.id, authToken.value],
  () => emit('close'),
  { flush: 'sync' },
)
onBeforeUnmount(() => {
  generation++
  controller?.abort()
  lightbox.value?.close()
})
onUnmounted(() => {
  // 等子面板和本轮列表更新完成；已读或实时刷新可能替换原按钮，按稳定消息ID重新定位。
  void nextTick(() => {
    const row = props.notificationId
      ? Array.from(
          globalThis.document.querySelectorAll<globalThis.HTMLElement>('[data-notification-id]'),
        ).find((item) => item.dataset.notificationId === props.notificationId)
      : undefined
    const target =
      row?.querySelector<globalThis.HTMLButtonElement>('.notification-card') ?? returnFocus
    if (target?.isConnected) target.focus({ preventScroll: true })
  })
})
</script>
<template>
  <ReviewDiscussion
    :review-id="reviewId"
    :username="detail?.item.username ?? ''"
    standalone
    :pending="loading || !detail"
    :focused-reply="detail?.focusedReply"
    @close="emit('close')"
  >
    <template #original="{ focusInput }">
      <p v-if="loading" role="status" class="notification-detail-state">正在加载点评…</p>
      <div v-else-if="error" role="alert" class="notification-detail-state">
        <p>{{ error }}</p>
        <button type="button" @click="load">重试加载点评</button>
      </div>
      <ReviewDetailOriginal
        v-else-if="detail"
        :item="detail.item"
        :authenticated="auth.authenticated"
        :viewer-id="auth.user ? String(auth.user.id) : undefined"
        store-name="查看店铺"
        :store-to="storeTo"
        @reply="focusInput"
        @photo="viewPhoto"
      />
    </template>
  </ReviewDiscussion>
  <Teleport to="body"
    ><dialog ref="lightbox" class="notification-review-photo" aria-label="点评照片大图">
      <button type="button" aria-label="关闭大图" @click="lightbox?.close()">关闭 ×</button>
      <img v-if="photo" :src="photo" alt="点评照片大图" /></dialog
  ></Teleport>
</template>
<style scoped>
.notification-detail-state {
  margin: 0;
  padding: 24px 20px;
  color: #85919f;
  font-size: 13px;
  line-height: 1.8;
}
.notification-detail-state button {
  min-height: 44px;
  padding: 0;
  border: 0;
  background: none;
  color: #a7783d;
  cursor: pointer;
}
.notification-review-photo {
  max-width: min(96vw, 920px);
  max-height: 95vh;
  max-height: 95dvh;
  border: 0;
  border-radius: 12px;
  padding: 14px;
}
.notification-review-photo::backdrop {
  background: #17212b99;
}
.notification-review-photo button {
  display: block;
  margin-left: auto;
  min-height: 44px;
  border: 0;
  background: transparent;
  color: #6c7785;
  cursor: pointer;
}
.notification-review-photo img {
  display: block;
  max-width: 100%;
  max-height: 78vh;
  max-height: 78dvh;
  object-fit: contain;
}
</style>
