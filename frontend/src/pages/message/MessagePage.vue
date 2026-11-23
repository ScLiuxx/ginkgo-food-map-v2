<script setup lang="ts">
import { useLoginStore } from '@/modules/auth'
import { MessageCenter } from '@/modules/message'
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useNotificationInbox } from '@/modules/message/model/useNotificationInbox'
import type { NotificationItem } from '@/modules/message/api/notificationsApi'
import { authToken } from '@/shared/api/authSession'
import { AnnouncementReader } from '@/modules/announcement'
import { ReviewNotificationDetail } from '@/modules/review'
import { ReportResultDialog, reportDecisionLabel } from '@/modules/moderation'
import { userProfilePath } from '@/shared/lib/userProfileNavigation'

// 页面编排真实收件箱和固定站内跳转；摘要绝不提供可执行HTML或任意目标地址。
const props = defineProps<{ category?: string }>()
const auth = useLoginStore()
const router = useRouter()
const state = useNotificationInbox(() => props.category)
const { result, loading, error, actionError, notice, busy } = state
const replyTarget = ref<NotificationItem | null>(null)
const announcementTarget = ref<string | null>(null)
const reportTarget = ref<NotificationItem | null>(null)
const pendingDelete = ref<NotificationItem | null>(null)
const reportDecision = computed(() => {
  const item = reportTarget.value
  return item?.notificationType === 'REPORT_RESULT' &&
    (item.decision === 'REMOVED' || item.decision === 'DISMISSED')
    ? item.decision
    : null
})
watch(
  () => [props.category, auth.user?.id, authToken.value],
  () => {
    replyTarget.value = null
    announcementTarget.value = null
    reportTarget.value = null
    pendingDelete.value = null
  },
  { flush: 'sync' },
)
const sessions = computed(() => [
  {
    id: 'system',
    title: '系统通知',
    preview: result.value?.latestSystem
      ? result.value.latestSystem.notificationType === 'ANNOUNCEMENT'
        ? (result.value.latestSystem.announcementTitle ?? '')
        : result.value.latestSystem.notificationType === 'REPORT_RESULT'
          ? reportDecisionLabel(
              result.value.latestSystem.decision === 'DISMISSED' ? 'DISMISSED' : 'REMOVED',
            )
          : `${result.value.latestSystem.restaurantName} · ${result.value.latestSystem.approvalMode === 'LINKED_EXISTING' ? '店铺已收录' : result.value.latestSystem.decision === 'APPROVED' ? '审核通过' : '审核未通过'}`
      : '',
    unreadCount: result.value?.unreadCounts.SYSTEM,
  },
])
async function open(item: NotificationItem) {
  const sourceScroll = globalThis.scrollY
  const selected = await state.read(item)
  if (!selected) return
  // 已读重读会替换消息节点；先恢复来源阅读位置和入口，再打开共用详情。
  await nextTick()
  globalThis.scrollTo({ top: sourceScroll, behavior: 'instant' })
  const source = Array.from(
    globalThis.document.querySelectorAll<globalThis.HTMLElement>('[data-notification-id]'),
  ).find((row) => row.dataset.notificationId === selected.id)
  source
    ?.querySelector<globalThis.HTMLButtonElement>('.notification-card')
    ?.focus({ preventScroll: true })
  if (selected?.notificationType === 'ANNOUNCEMENT' && selected.announcementId)
    announcementTarget.value = selected.announcementId
  // 举报结果只展示处置说明，不跳转内容页：被删除的目标已无法访问。
  else if (selected?.notificationType === 'REPORT_RESULT') reportTarget.value = selected
  else if (selected?.category === 'SYSTEM' && selected.requestId)
    await router.push({ path: '/account/contributions', query: { requestId: selected.requestId } })
  else if (selected?.category === 'FOLLOWERS' && selected.actorUserId)
    await router.push(userProfilePath(selected.actorUserId, auth.user?.id))
  else if (selected?.category === 'COMMENTS' || selected?.category === 'LIKES')
    replyTarget.value = selected
}

/** 确认对象只属于当前页面身份；状态层再校验当前列表，迟到结果不能关闭新身份的确认。 */
async function remove() {
  const selected = pendingDelete.value
  if (!selected) return
  const removed = await state.remove(selected)
  if (removed && pendingDelete.value === selected) pendingDelete.value = null
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
    :action-notice="notice"
    :deleting-id="pendingDelete?.id"
    :busy="busy"
    @refresh="state.load()"
    @page="state.load($event)"
    @read="open"
    @read-all="state.read()"
    @remove="pendingDelete = $event"
    @cancel-remove="pendingDelete = null"
    @confirm-remove="remove"
  />
  <AnnouncementReader
    v-if="announcementTarget"
    :id="announcementTarget"
    @close="announcementTarget = null"
  />
  <ReportResultDialog
    v-if="reportDecision"
    :decision="reportDecision"
    @close="reportTarget = null"
  />
  <ReviewNotificationDetail
    v-if="replyTarget?.reviewId"
    :key="replyTarget.id"
    :notification-id="replyTarget.id"
    :source-id="replyTarget.sourceId"
    :review-id="replyTarget.reviewId"
    :type="
      replyTarget.category === 'COMMENTS'
        ? 'COMMENT'
        : replyTarget.notificationType === 'REPLY_LIKED'
          ? 'REPLY_LIKED'
          : 'REVIEW_LIKED'
    "
    @close="replyTarget = null"
  />
</template>
