<script setup lang="ts">
import { ref, watch, onBeforeUnmount, useTemplateRef } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { authToken } from '@/shared/api/authSession'
import { useAdminAnnouncements } from '../model/useAdminAnnouncements'
import type { AnnouncementDetail } from '@/modules/announcement'
import AdminSidebar from './AdminSidebar.vue'
defineProps<{ username: string; loggingOut: boolean; sessionError: string | null }>()
defineEmits<{ logout: [] }>()
const state = useAdminAnnouncements()
const {
  tab,
  title,
  content,
  status,
  result,
  detail,
  busy,
  loading,
  inspecting,
  error,
  actionError,
  notice,
  pending,
  page,
  dirty,
  validForm,
} = state
const confirmation = useTemplateRef('confirmation'),
  recordDialog = useTemplateRef('recordDialog')
const confirming = ref<{ kind: 'publish' | 'clear' | 'withdraw'; row?: AnnouncementDetail } | null>(
  null,
)
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
function ask(kind: 'publish' | 'clear' | 'withdraw', row?: AnnouncementDetail) {
  if (busy.value) return
  actionError.value = ''
  if (kind === 'publish' && !pending.value && !validForm.value) {
    actionError.value = '请填写公告标题和正文，标题最多120字，正文最多10000字。'
    return
  }
  confirming.value = { kind, row }
  confirmation.value?.showModal()
}
function closeConfirmation() {
  if (busy.value) return
  confirmation.value?.close()
  confirming.value = null
}
async function confirm() {
  const action = confirming.value
  if (!action || busy.value) return
  if (action.kind === 'clear') {
    state.clearDraft()
    closeConfirmation()
    return
  }
  const success =
    action.kind === 'publish'
      ? await state.publish()
      : action.row
        ? await state.withdraw(action.row.id)
        : false
  if (success) closeConfirmation()
}
function inspect(id: string) {
  recordDialog.value?.showModal()
  void state.inspect(id)
}
function leave(event: globalThis.BeforeUnloadEvent) {
  if (dirty.value || busy.value) event.preventDefault()
}
globalThis.addEventListener('beforeunload', leave)
onBeforeRouteLeave(
  () => !busy.value && (!dirty.value || globalThis.confirm('尚有未发布或未确认的公告，确认离开？')),
)
watch(
  authToken,
  () => {
    confirmation.value?.close()
    recordDialog.value?.close()
    confirming.value = null
  },
  { flush: 'sync' },
)
onBeforeUnmount(() => {
  globalThis.removeEventListener('beforeunload', leave)
  confirmation.value?.close()
  recordDialog.value?.close()
})
</script>
<template>
  <div class="announcement-workspace">
    <header class="announcement-topbar">
      <RouterLink class="brand" to="/admin/reviews"
        ><em>泥电</em><span>美食地图</span><small>管理工作台</small></RouterLink
      >
      <div class="admin-identity">
        <span class="admin-avatar" aria-hidden="true">管</span
        ><span :title="username">{{ username }}</span
        ><button :disabled="loggingOut || busy" @click="$emit('logout')">
          {{ loggingOut ? '正在退出…' : '退出登录' }}
        </button>
      </div>
    </header>
    <p v-if="sessionError" role="alert" class="announcement-error">{{ sessionError }}</p>
    <div class="announcement-shell">
      <AdminSidebar />
      <main>
        <div class="announcement-breadcrumb">管理工作台 / 公告管理</div>
        <h1>公告管理</h1>
        <div class="announcement-tabs" aria-label="公告管理视图">
          <button
            :class="{ active: tab === 'publish' }"
            :aria-pressed="tab === 'publish'"
            :disabled="busy"
            @click="tab = 'publish'"
          >
            发布公告</button
          ><button
            :class="{ active: tab === 'records' }"
            :aria-pressed="tab === 'records'"
            :disabled="busy"
            @click="tab = 'records'"
          >
            公告记录
          </button>
        </div>
        <p v-if="notice" role="status" class="announcement-notice">{{ notice }}</p>
        <form v-if="tab === 'publish'" class="announcement-editor" @submit.prevent="ask('publish')">
          <h2>发布全体公告</h2>
          <p class="announcement-audience">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <circle cx="9" cy="7" r="4" />
              <path d="M2 21v-3a7 7 0 0 1 14 0v3M16 3a4 4 0 0 1 0 8M18 14a6 6 0 0 1 4 6" /></svg
            >所有用户可见
          </p>
          <div class="announcement-field">
            <label for="announcement-title">公告标题 <span>*</span></label
            ><input
              id="announcement-title"
              v-model="title"
              required
              :readonly="!!pending"
              :disabled="busy"
              autocomplete="off"
            /><small :class="{ invalid: Array.from(title).length > 120 }">{{
              Array.from(title).length > 120 ? '标题超过120字' : '最多 120 字'
            }}</small>
          </div>
          <div class="announcement-field">
            <label for="announcement-content">公告正文 <span>*</span></label
            ><textarea
              id="announcement-content"
              v-model="content"
              required
              :readonly="!!pending"
              :disabled="busy"
            /><small :class="{ invalid: Array.from(content).length > 10000 }">{{
              Array.from(content).length > 10000 ? '正文超过10000字' : '最多 10000 字'
            }}</small>
          </div>
          <p class="announcement-reminder">
            <span aria-hidden="true">ⓘ</span>发布后不可编辑，如需更正可撤回后重新发布。
          </p>
          <p v-if="actionError" role="alert" class="announcement-error">{{ actionError }}</p>
          <footer class="announcement-actions">
            <button type="button" :disabled="busy || !!pending || !dirty" @click="ask('clear')">
              清空内容</button
            ><button class="primary" type="submit" :disabled="busy">
              {{ busy ? '正在发布…' : pending ? '重试发布' : '发布公告' }}
            </button>
          </footer>
        </form>
        <section v-else class="announcement-records" aria-label="公告记录">
          <div class="announcement-record-toolbar">
            <label
              >状态
              <select v-model="status" :disabled="busy">
                <option value="ALL">全部状态</option>
                <option value="PUBLISHED">已发布</option>
                <option value="WITHDRAWN">已撤回</option>
              </select></label
            ><button :disabled="loading || busy" @click="state.load()">刷新</button>
          </div>
          <p v-if="actionError" role="alert" class="announcement-error">{{ actionError }}</p>
          <p v-if="loading" role="status">正在加载公告…</p>
          <div v-else-if="error" role="alert">
            <p>{{ error }}</p>
            <button @click="state.load()">重试加载</button>
          </div>
          <template v-else-if="result"
            ><p v-if="!result.records.length" class="announcement-empty">
              {{ result.total ? '当前页记录已变化，请返回第一页。' : '暂无公告记录' }}
            </p>
            <ul v-else class="announcement-record-list">
              <li v-for="row in result.records" :key="row.id">
                <div>
                  <button class="record-title" :disabled="busy" @click="inspect(row.id)">
                    {{ row.title }}
                  </button>
                  <p>
                    <time>{{ time(row.publishedAt) }}</time
                    ><span>发布人 #{{ row.publishedBy }}</span>
                  </p>
                </div>
                <span
                  class="announcement-status"
                  :class="{ withdrawn: row.status === 'WITHDRAWN' }"
                  >{{ row.status === 'PUBLISHED' ? '已发布' : '已撤回' }}</span
                >
                <div class="record-actions">
                  <button :disabled="busy" @click="inspect(row.id)">查看</button
                  ><button
                    v-if="row.status === 'PUBLISHED'"
                    :disabled="busy"
                    @click="ask('withdraw', row)"
                  >
                    撤回
                  </button>
                </div>
              </li>
            </ul>
            <div class="announcement-pagination">
              <span>共 {{ result.total }} 条 · 第 {{ page }} / {{ result.totalPages || 1 }} 页</span
              ><button :disabled="page <= 1 || busy" @click="state.load(page - 1)">上一页</button
              ><button :disabled="page >= result.totalPages || busy" @click="state.load(page + 1)">
                下一页
              </button>
            </div>
          </template>
        </section>
      </main>
    </div>
    <dialog
      ref="confirmation"
      class="announcement-dialog"
      aria-labelledby="announcement-confirm-title"
      @cancel="busy ? $event.preventDefault() : closeConfirmation()"
    >
      <h2 id="announcement-confirm-title">
        {{
          confirming?.kind === 'clear'
            ? '清空公告内容？'
            : confirming?.kind === 'withdraw'
              ? '确认撤回公告？'
              : '确认发布全体公告？'
        }}
      </h2>
      <template v-if="confirming?.kind === 'publish'"
        ><p class="confirmation-title">{{ pending?.title || title }}</p>
        <p>公告将对所有用户可见，发布后不可编辑。</p></template
      >
      <template v-else-if="confirming?.kind === 'withdraw'"
        ><p class="confirmation-title">{{ confirming.row?.title }}</p>
        <p>撤回后，用户将无法查看这条公告。</p></template
      >
      <p v-else>清空后需要重新填写标题和正文。</p>
      <p v-if="actionError" role="alert" class="announcement-error">{{ actionError }}</p>
      <footer class="announcement-actions">
        <button :disabled="busy" @click="closeConfirmation">取消</button
        ><button class="primary" :disabled="busy" @click="confirm">
          {{
            busy
              ? '正在提交…'
              : confirming?.kind === 'clear'
                ? '确认清空'
                : confirming?.kind === 'withdraw'
                  ? '确认撤回'
                  : '确认发布'
          }}
        </button>
      </footer>
    </dialog>
    <dialog ref="recordDialog" class="announcement-dialog record-dialog" aria-label="公告详情">
      <button class="dialog-close" aria-label="关闭公告详情" @click="recordDialog?.close()">
        ×
      </button>
      <p v-if="inspecting" role="status">正在加载公告…</p>
      <p v-else-if="!detail" role="alert">{{ actionError || '公告暂时不可用，请关闭后重试。' }}</p>
      <article v-else>
        <span class="announcement-status">{{
          detail.status === 'PUBLISHED' ? '已发布' : '已撤回'
        }}</span>
        <h2>{{ detail.title }}</h2>
        <p class="announcement-detail-meta">
          {{ time(detail.publishedAt) }} · 发布人 #{{ detail.publishedBy }}
        </p>
        <p class="announcement-body">{{ detail.content }}</p>
        <p v-if="detail.withdrawnAt" class="announcement-detail-meta">
          {{ time(detail.withdrawnAt) }} · 撤回人 #{{ detail.withdrawnBy }}
        </p>
      </article>
    </dialog>
  </div>
</template>
<style scoped src="./admin-announcements.css"></style>
