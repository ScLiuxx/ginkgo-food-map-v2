<script setup lang="ts">
import { useLoginStore } from '@/modules/auth'
import { MessageCenter } from '@/modules/message'
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useNotificationInbox } from '@/modules/message/model/useNotificationInbox'
import type { NotificationItem } from '@/modules/message/api/notificationsApi'
import { authToken } from '@/shared/api/authSession'
import LikeNotificationTarget from '@/modules/community/ui/LikeNotificationTarget.vue'
import ReplyNotificationTarget from '@/modules/review/ui/ReplyNotificationTarget.vue'

// 页面编排真实收件箱和固定站内跳转；摘要绝不提供可执行HTML或任意目标地址。
const props = defineProps<{ category?: string }>()
const auth = useLoginStore()
const router = useRouter()
const state = useNotificationInbox(() => props.category)
const { result, loading, error, actionError, busy } = state
const replyTarget = ref<NotificationItem | null>(null)
watch(
  () => [props.category, auth.user?.id, authToken.value],
  () => {
    replyTarget.value = null
  },
  { flush: 'sync' },
)
const sessions = computed(() => [
  {
    id: 'system',
    title: '系统通知',
    preview: result.value?.latestSystem
      ? `${result.value.latestSystem.restaurantName} · ${result.value.latestSystem.decision === 'APPROVED' ? '审核通过' : '审核未通过'}`
      : '',
    unreadCount: result.value?.unreadCounts.SYSTEM,
  },
])
async function open(item: NotificationItem) {
  const selected = await state.read(item)
  if (selected?.category === 'SYSTEM' && selected.requestId)
    await router.push({ path: '/account/contributions', query: { requestId: selected.requestId } })
  else if (selected?.category === 'FOLLOWERS' && selected.actorUserId)
    await router.push(`/users/${selected.actorUserId}`)
  else if (selected?.category === 'COMMENTS' || selected?.category === 'LIKES')
    replyTarget.value = selected
}
</script>

<template>
  <MessageCenter
    :authenticated="auth.authenticated"
    :category="category"
    :connected="auth.authenticated"
    :sessions="sessions"
    :inbox="result"
    :loading="loading"
    :error="error"
    :action-error="actionError"
    :busy="busy"
    @refresh="state.load()"
    @page="state.load($event)"
    @read="open"
    @read-all="state.read()"
  />
  <ReplyNotificationTarget
    v-if="replyTarget?.category === 'COMMENTS' && replyTarget.reviewId"
    :reply-id="replyTarget.sourceId"
    :review-id="replyTarget.reviewId"
    @close="replyTarget = null"
  />
  <LikeNotificationTarget
    v-if="replyTarget?.category === 'LIKES'"
    :id="replyTarget.sourceId"
    :type="replyTarget.notificationType === 'REPLY_LIKED' ? 'REPLY' : 'REVIEW'"
    @close="replyTarget = null"
  />
</template>
