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
import PhotoViewer from '@/shared/ui/PhotoViewer.vue'
import type { ReviewPhotoSelection } from '../model/review'

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
const lightbox = ref<InstanceType<typeof PhotoViewer>>()
const returnFocus =
  globalThis.document.activeElement instanceof globalThis.HTMLElement
    ? globalThis.document.activeElement
    : undefined
const storeTo = computed(() =>
  detail.value ? `/restaurants/${detail.value.restaurantId}` : undefined,
)
let generation = 0,
  controller: globalThis.AbortController | undefined
async function load() {
  lightbox.value?.close()
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
function viewPhoto(selection: ReviewPhotoSelection) {
  void lightbox.value?.open(selection)
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
  <PhotoViewer ref="lightbox" />
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
</style>
