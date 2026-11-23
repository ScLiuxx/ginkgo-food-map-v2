<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, useId, watch } from 'vue'
import type { NotificationItem } from '../api/notificationsApi'
import { notificationTime } from '../model/notificationTime'
import UserAvatar from '@/modules/account/ui/UserAvatar.vue'

/** 四类消息共用只读摘要与次要操作；读取和删除仍由页面固定身份后提交。 */
const props = defineProps<{ item: NotificationItem; busy: boolean; deleting: boolean }>()
const emit = defineEmits<{
  read: [NotificationItem]
  remove: [NotificationItem]
  cancelRemove: []
  confirmRemove: []
}>()
const root = ref<globalThis.HTMLLIElement>()
const trigger = ref<globalThis.HTMLButtonElement>()
const deleteAction = ref<globalThis.HTMLButtonElement>()
const cancelAction = ref<globalThis.HTMLButtonElement>()
const opened = ref(false)
const panelId = `notification-actions-${useId()}`

// 摘要只使用现有协议字段，用户头像通过统一组件读取，系统图标保持独立。
const presentation = computed(() => {
  const item = props.item
  if (item.category !== 'SYSTEM')
    return {
      title: item.actorUsername ?? '',
      detail:
        item.category === 'FOLLOWERS'
          ? '关注了你'
          : item.category === 'COMMENTS'
            ? '回复了你的点评'
            : `赞了你的${item.notificationType === 'REPLY_LIKED' ? '回复' : '点评'}`,
      icon: null,
    }
  if (item.notificationType === 'ANNOUNCEMENT')
    return { title: '系统公告', detail: item.announcementTitle, icon: 'announcement' }
  if (item.notificationType === 'REPORT_RESULT')
    return {
      title: '举报处理结果',
      detail: item.decision === 'REMOVED' ? '举报已处理：内容已删除' : '举报未认定违规',
      icon: 'report',
    }
  return {
    title:
      item.approvalMode === 'LINKED_EXISTING'
        ? '店铺已收录'
        : `${item.requestType === 'CREATE' ? '新店投稿' : '资料纠错'}${item.decision === 'APPROVED' ? '审核通过' : '审核未通过'}`,
    detail: item.restaurantName,
    icon: item.decision === 'APPROVED' ? 'approved' : 'edit',
  }
})
// 用户ID分配稳定的浅色头像；不按列表顺序变色，不表达在线、等级或关系状态。
const avatarTone = computed(() => Number(BigInt(props.item.actorUserId ?? '0') % 3n))
const fullTime = computed(() =>
  new Date(props.item.createdAt).toLocaleString('zh-CN', { hour12: false }),
)

function close(restoreFocus = false) {
  opened.value = false
  if (restoreFocus) trigger.value?.focus({ preventScroll: true })
}
async function expandWithKeyboard() {
  if (props.busy || props.deleting) return
  opened.value = true
  await nextTick()
  deleteAction.value?.focus()
}
function outside(event: globalThis.PointerEvent) {
  if (event.target instanceof globalThis.Node && !root.value?.contains(event.target)) close()
}
function leave(event: globalThis.FocusEvent) {
  if (event.relatedTarget instanceof globalThis.Node && !root.value?.contains(event.relatedTarget))
    close()
}
function requestRemove() {
  close()
  emit('remove', props.item)
}
function escape() {
  if (props.deleting) {
    if (!props.busy) emit('cancelRemove')
  } else if (opened.value) close(true)
}
// 删除确认只切换当前行；取消恢复可见入口，操作失败时继续保留确认与重试能力。
watch(
  () => props.deleting,
  (deleting, previous) => {
    close()
    if (deleting) cancelAction.value?.focus({ preventScroll: true })
    else if (previous) trigger.value?.focus({ preventScroll: true })
  },
  { flush: 'post' },
)
watch(
  () => props.busy,
  (busy) => {
    if (busy) close()
  },
)
onMounted(() => globalThis.document.addEventListener('pointerdown', outside))
onUnmounted(() => globalThis.document.removeEventListener('pointerdown', outside))
</script>

<template>
  <li
    ref="root"
    class="notification-entry"
    :data-notification-id="item.id"
    :class="{ 'system-entry': item.category === 'SYSTEM' }"
    @focusout="leave"
    @keydown.esc.stop.prevent="escape"
  >
    <button type="button" class="notification-card" :disabled="busy" @click="emit('read', item)">
      <UserAvatar
        v-if="!presentation.icon"
        class="notification-avatar"
        :class="`avatar-tone-${avatarTone}`"
        :user-id="item.actorUserId"
        :username="item.actorUsername"
      />
      <span
        v-else
        class="notification-avatar"
        :class="
          presentation.icon ? ['system-avatar', presentation.icon] : `avatar-tone-${avatarTone}`
        "
        aria-hidden="true"
      >
        <svg v-if="presentation.icon" viewBox="0 0 24 24" fill="none">
          <path v-if="presentation.icon === 'approved'" d="m6 12 4 4 8-9" />
          <path
            v-else-if="presentation.icon === 'announcement'"
            d="M18 8a6 6 0 0 0-12 0c0 7-2 7-2 9h16c0-2-2-2-2-9M10 21h4"
          />
          <path
            v-else-if="presentation.icon === 'report'"
            d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6zM12 8v5M12 16v.1"
          />
          <path v-else d="m15 4 5 5M5 15 16 4a2 2 0 0 1 4 4L9 19l-5 1z" />
        </svg>
      </span>
      <span class="notification-copy">
        <strong class="notification-title" :title="presentation.title">{{
          presentation.title
        }}</strong>
        <span class="notification-detail">{{ presentation.detail }}</span>
        <span v-if="item.rejectReason" class="notification-reason"
          >驳回原因：{{ item.rejectReason }}</span
        >
        <time :datetime="item.createdAt" :title="fullTime">{{
          notificationTime(item.createdAt)
        }}</time>
      </span>
      <span v-if="!item.readAt" class="notification-unread" aria-label="未读消息"></span>
    </button>
    <!-- 与整行读取按钮并列，点更多或删除不会触发已读或打开内容。 -->
    <div class="notification-actions">
      <button
        ref="trigger"
        type="button"
        class="notification-more"
        :aria-label="`${presentation.title}的消息操作`"
        title="更多操作"
        :aria-expanded="opened"
        :aria-controls="panelId"
        :disabled="busy || deleting"
        @click="opened = !opened"
        @keydown.down.prevent="expandWithKeyboard"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <circle cx="5" cy="12" r="1" />
          <circle cx="12" cy="12" r="1" />
          <circle cx="19" cy="12" r="1" />
        </svg>
      </button>
      <div v-if="opened" :id="panelId" class="notification-menu" role="group" aria-label="消息操作">
        <button ref="deleteAction" type="button" :disabled="busy" @click="requestRemove">
          删除消息
        </button>
      </div>
    </div>
    <div v-if="deleting" class="notification-delete-confirm" role="group" aria-label="确认删除消息">
      <p>确定删除这条消息？原内容不受影响。</p>
      <div>
        <button ref="cancelAction" type="button" :disabled="busy" @click="emit('cancelRemove')">
          取消
        </button>
        <button
          type="button"
          class="confirm-delete"
          :disabled="busy"
          @click="emit('confirmRemove')"
        >
          {{ busy ? '删除中…' : '确认删除' }}
        </button>
      </div>
    </div>
  </li>
</template>

<style scoped src="./message-notification.css"></style>
