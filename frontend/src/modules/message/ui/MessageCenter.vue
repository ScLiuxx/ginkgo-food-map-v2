<script setup lang="ts">
import { computed } from 'vue'
import {
  initialMessageSessions,
  messageCategories,
  type MessageSession,
} from '../model/messageCategories'
import MessageIcon from './MessageIcon.vue'
import type { NotificationItem, NotificationPage } from '../api/notificationsApi'

const props = withDefaults(
  defineProps<{
    authenticated: boolean
    category?: string
    sessions?: readonly MessageSession[]
    connected?: boolean
    inbox?: NotificationPage | null
    loading?: boolean
    error?: string
    actionError?: string
    actionNotice?: string
    deletingId?: string
    busy?: boolean
  }>(),
  {
    category: undefined,
    sessions: () => initialMessageSessions,
    inbox: null,
    connected: false,
    loading: false,
    error: '',
    actionError: '',
    actionNotice: '',
    deletingId: undefined,
    busy: false,
  },
)
defineEmits<{
  refresh: []
  page: [number]
  read: [NotificationItem]
  readAll: []
  remove: [NotificationItem]
  cancelRemove: []
  confirmRemove: []
}>()
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
const canReadAll = computed(
  () => props.connected && !!props.inbox?.unreadCount && !props.loading && !props.busy,
)
const shortcuts = messageCategories.slice(0, 3)
const shortcutUnread = (id: string) =>
  id === 'likes'
    ? (props.inbox?.unreadCounts.LIKES ?? 0)
    : id === 'comments'
      ? (props.inbox?.unreadCounts.COMMENTS ?? 0)
      : (props.inbox?.unreadCounts.FOLLOWERS ?? 0)
// 分类由路由传入，支持刷新和浏览器前进/后退；未知分类回到中心展示，不拼接任意跳转。
const selected = computed(() => messageCategories.find((item) => item.id === props.category))
const loginTarget = computed(() => ({
  path: '/login',
  query: { redirect: selected.value ? `/messages/${selected.value.id}` : '/messages' },
}))
</script>

<template>
  <section class="message-center" aria-labelledby="message-heading">
    <template v-if="!selected">
      <header class="message-heading">
        <div class="message-title">
          <h1 id="message-heading">消息</h1>
          <span v-if="!connected" class="availability">即将开放</span>
        </div>
        <!-- 读取成功且存在未读时才开放写入；操作结果以服务端重读为准。 -->
        <button
          class="mark-all-read"
          :disabled="!canReadAll"
          title="将全部已有消息标为已读"
          @click="$emit('readAll')"
        >
          全部已读
        </button>
      </header>

      <nav class="message-shortcuts" aria-label="互动消息分类">
        <RouterLink
          v-for="item in shortcuts"
          :key="item.id"
          :to="`/messages/${item.id}`"
          class="message-shortcut"
          :class="item.tone"
        >
          <span class="shortcut-icon"><MessageIcon :name="item.icon" /></span>
          <strong>{{ item.title }}</strong>
          <span v-if="shortcutUnread(item.id)" class="shortcut-unread">{{
            Math.min(99, shortcutUnread(item.id))
          }}</span>
        </RouterLink>
      </nav>

      <!-- 系统通知统一采用会话行；时间及未读数仅在调用方提供真实值时展示。 -->
      <nav v-if="sessions.length" class="session-list" aria-label="消息会话列表">
        <RouterLink
          v-for="item in sessions"
          :key="item.id"
          to="/messages/system"
          class="session-row blue"
        >
          <span class="session-avatar"><MessageIcon name="announcement" /></span>
          <span class="session-copy">
            <span class="session-heading">
              <strong>{{ item.title }}</strong>
              <span v-if="item.timeLabel" class="session-time">{{ item.timeLabel }}</span>
            </span>
            <span
              v-if="item.preview || (item.unreadCount && item.unreadCount > 0)"
              class="session-summary"
            >
              <span v-if="item.preview" class="session-preview">{{ item.preview }}</span>
              <span
                v-if="item.unreadCount && item.unreadCount > 0"
                class="session-unread"
                :aria-label="`${item.unreadCount} 条未读消息`"
                >{{ item.unreadCount > 99 ? '99+' : item.unreadCount }}</span
              >
            </span>
          </span>
        </RouterLink>
      </nav>

      <!-- 仅整个会话列表为空时展示一份空态，避免系统会话下方出现无关的私信占位。 -->
      <div v-else class="session-empty message-empty">
        <span class="empty-illustration"><MessageIcon name="inbox" /></span>
        <h2>消息功能即将开放</h2>
        <p>与你有关的通知，将在这里与你见面。</p>
        <RouterLink class="empty-action" to="/"
          >去发现美食 <span aria-hidden="true">→</span></RouterLink
        >
      </div>
    </template>

    <template v-else>
      <header class="category-heading">
        <RouterLink class="back-link" to="/messages" aria-label="返回消息中心">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m14 6-6 6 6 6" /></svg>
          <span>消息</span>
        </RouterLink>
        <h1 id="message-heading">{{ selected.title }}</h1>
        <button
          class="mark-all-read"
          :disabled="
            !canReadAll || !['system', 'comments', 'likes', 'followers'].includes(selected.id)
          "
          title="仅将当前分类的已有消息标为已读"
          @click="$emit('readAll')"
        >
          全部已读
        </button>
      </header>
      <div
        v-if="['system', 'comments', 'likes', 'followers'].includes(selected.id) && connected"
        class="notification-inbox"
        :aria-busy="loading"
      >
        <div class="notification-toolbar">
          <span v-if="inbox">{{ inbox.unreadCount }} 条未读</span
          ><button type="button" :disabled="loading || busy" @click="$emit('refresh')">刷新</button>
        </div>
        <p v-if="loading" role="status">正在加载通知…</p>
        <div v-else-if="error" role="alert">
          <p>{{ error }}</p>
          <button type="button" @click="$emit('refresh')">重试加载</button>
        </div>
        <template v-else-if="inbox">
          <p v-if="!inbox.records.length" class="notification-empty">
            {{
              inbox.total
                ? '当前页记录已变化，请返回第一页。'
                : selected.id === 'comments'
                  ? '暂无评论提醒'
                  : selected.id === 'likes'
                    ? '暂无获赞提醒'
                    : selected.id === 'followers'
                      ? '暂无新增关注提醒'
                      : '暂无系统通知'
            }}
          </p>
          <ul v-else class="notification-list">
            <li v-for="item in inbox.records" :key="item.id">
              <button
                type="button"
                class="notification-card"
                :class="{ unread: !item.readAt }"
                :disabled="busy"
                @click="$emit('read', item)"
              >
                <span class="notification-meta"
                  ><strong v-if="item.category === 'COMMENTS'"
                    >{{ item.actorUsername }} 回复了你的点评</strong
                  ><strong v-else-if="item.category === 'LIKES'"
                    >{{ item.actorUsername }} 赞了你的{{
                      item.notificationType === 'REPLY_LIKED' ? '回复' : '点评'
                    }}</strong
                  ><strong v-else-if="item.category === 'FOLLOWERS'"
                    >{{ item.actorUsername }} 关注了你</strong
                  ><strong v-else-if="item.notificationType === 'ANNOUNCEMENT'">系统公告</strong
                  ><strong v-else-if="item.notificationType === 'REPORT_RESULT'">举报处理结果</strong
                  ><strong v-else
                    >{{ item.requestType === 'CREATE' ? '新店投稿' : '资料纠错'
                    }}{{ item.decision === 'APPROVED' ? '审核通过' : '审核未通过' }}</strong
                  ><span v-if="!item.readAt" class="notification-unread">未读</span></span
                >
                <span v-if="item.category === 'SYSTEM'" class="notification-name">{{
                  item.notificationType === 'ANNOUNCEMENT'
                    ? item.announcementTitle
                    : item.notificationType === 'REPORT_RESULT'
                      ? item.decision === 'REMOVED'
                        ? '举报已处理：内容已删除'
                        : '举报未认定违规'
                      : item.restaurantName
                }}</span>
                <span v-if="item.rejectReason" class="notification-reason"
                  >驳回原因：{{ item.rejectReason }}</span
                >
                <span class="notification-meta"
                  ><time>{{ time(item.createdAt) }}</time
                  ><span
                    >{{
                      item.category === 'COMMENTS'
                        ? '查看点评与回复'
                        : item.category === 'LIKES'
                          ? '查看获赞内容'
                          : item.category === 'FOLLOWERS'
                            ? '查看个人主页'
                            : item.notificationType === 'ANNOUNCEMENT'
                              ? '查看公告'
                              : item.notificationType === 'REPORT_RESULT'
                                ? '查看处理结果'
                                : '查看申请'
                    }}
                    ›</span
                  ></span
                >
              </button>
              <div
                v-if="deletingId === item.id"
                class="notification-delete-confirm"
                role="group"
                aria-label="确认删除消息"
              >
                <p>确定删除这条消息？原点评、回复或申请不受影响。</p>
                <button type="button" :disabled="busy" @click="$emit('cancelRemove')">
                  取消删除
                </button>
                <button type="button" :disabled="busy" @click="$emit('confirmRemove')">
                  {{ busy ? '删除中…' : '确认删除消息' }}
                </button>
              </div>
              <button
                v-else
                type="button"
                class="notification-delete"
                :disabled="busy"
                @click="$emit('remove', item)"
              >
                删除消息
              </button>
            </li>
          </ul>
          <nav
            v-if="inbox.totalPages > 1 || inbox.page > 1"
            class="notification-pagination"
            aria-label="通知分页"
          >
            <button :disabled="busy || inbox.page <= 1" @click="$emit('page', inbox.page - 1)">
              上一页</button
            ><span>{{ inbox.page }} / {{ inbox.totalPages || 1 }}</span
            ><button
              :disabled="busy || inbox.page >= inbox.totalPages"
              @click="$emit('page', inbox.page + 1)"
            >
              下一页</button
            ><button v-if="!inbox.records.length" :disabled="busy" @click="$emit('page', 1)">
              第一页
            </button>
          </nav>
        </template>
      </div>
      <div v-else class="category-empty message-empty" :class="selected.tone">
        <span class="category-illustration"><MessageIcon :name="selected.icon" /></span>
        <h2>{{ selected.title }}，即将开放</h2>
        <p>{{ selected.detail }}</p>
        <RouterLink v-if="!authenticated" class="empty-action" :to="loginTarget"
          >登录账号</RouterLink
        >
        <RouterLink v-else class="empty-action" to="/"
          >去发现美食 <span aria-hidden="true">→</span></RouterLink
        >
      </div>
    </template>
    <div
      v-if="connected && !selected && (loading || error)"
      class="notification-status"
      :role="error ? 'alert' : 'status'"
    >
      <p>{{ loading ? '正在加载通知…' : error }}</p>
      <button v-if="error" @click="$emit('refresh')">重试加载</button>
    </div>
    <p v-if="actionError" class="notification-status" role="alert">{{ actionError }}</p>
    <p v-if="actionNotice" class="notification-status" role="status">{{ actionNotice }}</p>
  </section>
</template>

<style scoped src="./message-center.css"></style>
