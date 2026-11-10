<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, useTemplateRef, watch } from 'vue'
import { authToken } from '@/shared/api/authSession'
import { contentReportStatusNames, useAdminContentReports } from '../model/useAdminContentReports'
import {
  reportReasonLabel,
  reportTargetLabel,
  type ContentReport,
  type ContentReportStatus,
} from '@/modules/moderation'
import AdminSidebar from './AdminSidebar.vue'

/** 管理页只负责协议展示与确认交互；权限校验和处置事务全部由服务端完成。 */
defineProps<{ username: string; loggingOut: boolean; sessionError: string | null }>()
defineEmits<{ logout: [] }>()
const state = useAdminContentReports()
const { status, page, result, detail, loading, inspecting, busy, error, actionError, notice } = state
const statuses: ContentReportStatus[] = ['PENDING', 'RESOLVED', 'DISMISSED']
const detailDialog = useTemplateRef('detailDialog'),
  confirmDialog = useTemplateRef('confirmDialog')
const note = ref(''),
  pendingRemoval = ref<ContentReport | null>(null)
const noteLength = computed(() => Array.from(note.value.trim()).length)
const time = (value: string | null) =>
  value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'

onMounted(() => void state.load(1))

function inspect(id: string) {
  note.value = ''
  detailDialog.value?.showModal()
  void state.inspect(id)
}

function closeDetail() {
  if (busy.value) return
  detailDialog.value?.close()
  pendingRemoval.value = null
}

function refresh() {
  if (busy.value) return
  detailDialog.value?.close()
  void state.load()
}

function askRemove() {
  if (!detail.value || busy.value || noteLength.value > 200) return
  pendingRemoval.value = detail.value
  confirmDialog.value?.showModal()
}

function closeConfirmation() {
  if (busy.value) return
  confirmDialog.value?.close()
  pendingRemoval.value = null
}

/** 处置成功后关闭详情，409也会关闭并提供刷新后的真实状态；失败保留弹窗供重试。 */
async function dismissReport() {
  const row = detail.value
  if (!row || busy.value || noteLength.value > 200) return
  const result = await state.dismiss(row.id, note.value)
  if (result !== 'failed') closeDetail()
}

async function confirmRemoval() {
  const row = pendingRemoval.value
  if (!row || busy.value || noteLength.value > 200) return
  const result = await state.remove(row.id, note.value)
  if (result !== 'failed') {
    confirmDialog.value?.close()
    pendingRemoval.value = null
    closeDetail()
  }
}

watch(authToken, () => {
  detailDialog.value?.close()
  confirmDialog.value?.close()
  pendingRemoval.value = null
  note.value = ''
})
onBeforeUnmount(() => {
  detailDialog.value?.close()
  confirmDialog.value?.close()
})
</script>

<template>
  <div class="report-workspace">
    <header class="report-topbar">
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
    <p v-if="sessionError" role="alert" class="report-error">{{ sessionError }}</p>
    <div class="report-shell">
      <AdminSidebar />
      <main>
        <div class="report-breadcrumb">管理工作台 / 举报处理</div>
        <h1>举报处理</h1>
        <p class="report-intro">核查同学提交的举报，驳回误报或永久删除违规内容。</p>

        <div class="report-tabs" aria-label="举报状态筛选">
          <button
            v-for="item in statuses"
            :key="item"
            :class="{ active: status === item }"
            :aria-pressed="status === item"
            :disabled="busy"
            @click="status = item"
          >
            {{ contentReportStatusNames[item] }}
          </button>
        </div>
        <p v-if="notice" role="status" class="report-notice">{{ notice }}</p>
        <p v-if="actionError && !detail" role="alert" class="report-error">{{ actionError }}</p>

        <section class="report-panel" aria-label="举报列表">
          <div class="report-toolbar">
            <span>
              {{ loading ? '正在加载…' : result ? `共 ${result.total} 条` : '等待加载' }}
            </span>
            <button :disabled="loading || busy" @click="refresh">刷新</button>
          </div>
          <p v-if="loading" role="status">正在加载举报…</p>
          <div v-else-if="error" role="alert">
            <p>{{ error }}</p>
            <button @click="state.load()">重试加载</button>
          </div>
          <template v-else-if="result">
            <p v-if="!result.records.length" class="report-empty">
              {{
                result.total
                  ? '当前页记录已变化，请返回第一页。'
                  : status === 'PENDING'
                    ? '暂无待处理举报'
                    : `暂无${contentReportStatusNames[status]}的举报`
              }}
            </p>
            <ul v-else class="report-list">
              <li v-for="row in result.records" :key="row.id">
                <div class="report-row-main">
                  <p class="report-row-head">
                    <span class="report-status" :class="row.status.toLowerCase()">{{
                      contentReportStatusNames[row.status]
                    }}</span>
                    <strong>{{ reportTargetLabel(row.targetType) }} #{{ row.targetId }}</strong>
                    <span class="report-reason">{{ reportReasonLabel(row.reason) }}</span>
                  </p>
                  <p class="report-snapshot">{{ row.contentSnapshot }}</p>
                  <p class="report-meta">
                    <span>举报人 #{{ row.reporterUserId }}</span
                    ><span>作者 #{{ row.targetAuthorUserId }}</span
                    ><time :datetime="row.createdAt">{{ time(row.createdAt) }}</time>
                  </p>
                </div>
                <button :disabled="busy" @click="inspect(row.id)">查看详情</button>
              </li>
            </ul>
            <div class="report-pagination">
              <span>第 {{ page }} / {{ result.totalPages || 1 }} 页</span
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
      ref="detailDialog"
      class="report-dialog"
      aria-label="举报详情"
      @cancel="busy ? $event.preventDefault() : closeDetail()"
    >
      <button
        type="button"
        class="dialog-close"
        aria-label="关闭举报详情"
        :disabled="busy"
        @click="closeDetail"
      >
        ×
      </button>
      <p v-if="inspecting" role="status">正在加载举报详情…</p>
      <p v-else-if="!detail" role="alert">{{ actionError || '举报暂时不可用，请关闭后重试。' }}</p>
      <article v-else>
        <header class="report-detail-head">
          <span class="report-status" :class="detail.status.toLowerCase()">{{
            contentReportStatusNames[detail.status]
          }}</span>
          <span>{{ reportTargetLabel(detail.targetType) }} #{{ detail.targetId }}</span>
          <span>举报 #{{ detail.id }}</span>
        </header>
        <p class="report-detail-reason">
          举报原因：<strong>{{ reportReasonLabel(detail.reason) }}</strong>
        </p>
        <p v-if="detail.detail" class="report-detail-note">补充说明：{{ detail.detail }}</p>
        <h2>被举报内容快照</h2>
        <p class="report-snapshot detail">{{ detail.contentSnapshot }}</p>
        <p class="report-meta detail">
          <span>举报人 #{{ detail.reporterUserId }}</span
          ><span>作者 #{{ detail.targetAuthorUserId }}</span
          ><span>所属点评 #{{ detail.reviewId }}</span
          ><time :datetime="detail.createdAt">举报于 {{ time(detail.createdAt) }}</time>
        </p>
        <template v-if="detail.status === 'PENDING'">
          <label for="report-decision-note">处理备注 <span>选填，最多200字</span></label>
          <textarea
            id="report-decision-note"
            v-model="note"
            rows="3"
            :disabled="busy"
            :aria-invalid="noteLength > 200"
          />
          <p class="report-count" :class="{ invalid: noteLength > 200 }">
            {{ noteLength }} / 200
          </p>
          <p v-if="actionError" role="alert" class="report-error">{{ actionError }}</p>
          <footer class="report-decisions">
            <button :disabled="busy || noteLength > 200" @click="dismissReport">
              {{ busy ? '处理中…' : '驳回举报' }}
            </button>
            <button
              class="danger"
              :disabled="busy || noteLength > 200"
              @click="askRemove"
            >
              确认违规并删除
            </button>
          </footer>
        </template>
        <div v-else class="report-handled">
          <p>处理人 #{{ detail.handledBy }}</p>
          <p>处理时间 {{ time(detail.handledAt) }}</p>
          <p v-if="detail.decisionNote">处理备注：{{ detail.decisionNote }}</p>
        </div>
      </article>
    </dialog>

    <dialog
      ref="confirmDialog"
      class="report-dialog report-confirm"
      aria-labelledby="report-confirm-title"
      @cancel="busy ? $event.preventDefault() : closeConfirmation()"
    >
      <h2 id="report-confirm-title">确认永久删除内容？</h2>
      <p v-if="pendingRemoval" class="report-confirm-target">
        {{ reportTargetLabel(pendingRemoval.targetType) }} #{{
          pendingRemoval.targetId
        }}：{{ pendingRemoval.contentSnapshot }}
      </p>
      <p>删除后无法恢复，并将同一内容下的待处理举报一并结案。</p>
      <p v-if="actionError" role="alert" class="report-error">{{ actionError }}</p>
      <footer class="report-decisions">
        <button :disabled="busy" @click="closeConfirmation">取消</button>
        <button class="danger" :disabled="busy" @click="confirmRemoval">
          {{ busy ? '正在删除…' : '永久删除并结案' }}
        </button>
      </footer>
    </dialog>
  </div>
</template>

<style scoped src="./admin-reports.css"></style>
