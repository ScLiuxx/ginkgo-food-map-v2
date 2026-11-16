<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useLoginStore } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'
import { useMyRestaurantRequests } from '../model/useMyRestaurantRequests'
import {
  myRequestStatusNames,
  myRequestPageSizes,
  type MyRequestFilter,
  type MyRequestItem,
} from '../api/myRestaurantRequestsApi'
import MyContributionSnapshot from './MyContributionSnapshot.vue'
import { recommendationLabels } from '@/modules/review/model/review'
import { contributionChanges } from '../model/myContributionFields'

/** 本人详情展示私有点评，并按服务端状态开放修改、撤回、重提与后续申请入口。 */
const state = useMyRestaurantRequests()
const route = useRoute()
const auth = useLoginStore()
let openedTarget = ''
const {
  filter,
  pageSize,
  result,
  loading,
  error,
  selectedId,
  detail,
  detailLoading,
  detailError,
  photos,
} = state
const dialog = ref<globalThis.HTMLDialogElement>(),
  withdrawDialog = ref<globalThis.HTMLDialogElement>(),
  lightbox = ref<globalThis.HTMLDialogElement>(),
  preview = ref('')
const filters: MyRequestFilter[] = ['ALL', 'PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN']
const changes = computed(() => (detail.value ? contributionChanges(detail.value) : []))
const submittedName = computed(() => {
  const name = detail.value?.proposed.name
  return typeof name === 'string' && name.trim() ? name : detail.value?.application.name
})
const time = (value: string | null) =>
  value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '尚未审核'
/** 审核状态用于筛选和计数；关联已有店铺属于通过，但列表和详情保留独立的处理结果文案。 */
function resultName(item: MyRequestItem) {
  return item.status === 'APPROVED' && item.approvalMode === 'LINKED_EXISTING'
    ? '店铺已收录'
    : myRequestStatusNames[item.status]
}
/** 列表只展示短日期；跨年保留年份，详情继续提供完整时间。 */
function listTime(value: string) {
  const date = new Date(value)
  const pad = (part: number) => String(part).padStart(2, '0')
  const year = date.getFullYear() === new Date().getFullYear() ? '' : `${date.getFullYear()}-`
  return `${year}${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
function open(id: string) {
  void state.select(id)
  dialog.value?.showModal()
}
function close() {
  if (state.withdrawing.value) return
  dialog.value?.close()
  state.clearDetail()
}
function onDialogClosed() {
  if (!dialog.value?.open) state.clearDetail()
}
function viewPhoto(src: string) {
  preview.value = src
  lightbox.value?.showModal()
}
/** 明确解释撤回终态后再发送请求，不复用上次操作的失败提示。 */
function askWithdraw() {
  state.mutationError.value = ''
  withdrawDialog.value?.showModal()
}
/** 消息跳转只接收申请ID，真实归属仍由本人详情接口验证；等首次列表读取完成，避免加载清理弹窗。 */
watch(
  () => [route.query.requestId, auth.user?.id, authToken.value, loading.value],
  () => {
    const id = route.query.requestId
    if (
      loading.value ||
      !auth.authenticated ||
      !authToken.value ||
      typeof id !== 'string' ||
      !/^[1-9]\d{0,18}$/.test(id) ||
      BigInt(id) > 9223372036854775807n
    )
      return
    const target = `${auth.user?.id}:${authToken.value}:${id}`
    if (target === openedTarget) return
    openedTarget = target
    open(id)
  },
  { immediate: true, flush: 'post' },
)
watch(
  selectedId,
  (value) => {
    if (!value) {
      withdrawDialog.value?.close()
      lightbox.value?.close()
      dialog.value?.close()
      preview.value = ''
    }
  },
  { flush: 'sync' },
)
</script>
<template>
  <section class="my-contributions" aria-label="本人投稿与纠错记录" :aria-busy="loading">
    <p v-if="state.mutationMessage.value" class="contribution-result" role="status">
      {{ state.mutationMessage.value }}
    </p>
    <div class="contributions-list-panel">
      <div class="contributions-filters" role="group" aria-label="申请状态筛选">
        <button
          v-for="status in filters"
          :key="status"
          type="button"
          :aria-pressed="filter === status"
          :title="status === 'APPROVED' ? '包含新店、纠错审核通过和店铺已收录的申请' : undefined"
          @click="state.changeFilter(status)"
        >
          {{ myRequestStatusNames[status] }}<span v-if="result">{{ result.counts[status] }}</span>
        </button>
      </div>
      <p v-if="loading" class="contribution-status" role="status">正在加载申请记录…</p>
      <div v-else-if="error" class="contribution-error" role="alert">
        <p>{{ error }}</p>
        <button type="button" @click="state.load()">重试加载</button>
      </div>
      <div v-else-if="result && !result.records.length" class="contribution-empty">
        <h2>
          {{
            result.total
              ? '当前页记录已变化'
              : filter === 'ALL'
                ? '还没有投稿或纠错记录'
                : `暂无${myRequestStatusNames[filter]}的申请`
          }}
        </h2>
        <p>可以提交尚未收录的新店，也可以从店铺详情补充或纠正资料。</p>
        <button v-if="result.total" type="button" @click="state.load(1)">返回第一页</button>
        <RouterLink v-else to="/">去发现美食 →</RouterLink>
      </div>
      <ul v-else-if="result" class="contribution-list">
        <li v-for="item in result.records" :key="item.id">
          <button type="button" class="contribution-card" @click="open(item.id)">
            <span class="contribution-card-heading"
              ><strong :title="item.name">{{ item.name }}</strong
              ><span
                class="contribution-badge"
                :data-status="item.status"
                :title="
                  item.approvalMode === 'LINKED_EXISTING' ? '已通过：关联已有店铺' : undefined
                "
                >{{ resultName(item) }}</span
              ></span
            >
            <span class="contribution-meta"
              >{{ item.requestType === 'CREATE' ? '新店投稿' : '资料纠错' }} ·
              <time :datetime="item.createdAt" :title="time(item.createdAt)">{{
                listTime(item.createdAt)
              }}</time></span
            >
          </button>
        </li>
      </ul>
    </div>
    <nav
      v-if="result || loading || error"
      class="contribution-pagination"
      aria-label="申请记录分页"
    >
      <div class="contribution-page-options">
        <span v-if="result" class="contribution-total">共 {{ result.total }} 条</span>
        <label class="contribution-page-size"
          >每页
          <select
            :value="pageSize"
            aria-label="每页条数"
            @change="state.changePageSize(Number(($event.target as HTMLSelectElement).value))"
          >
            <option v-for="size in myRequestPageSizes" :key="size" :value="size">
              {{ size }}
            </option></select
          >条
        </label>
      </div>
      <div v-if="result && result.totalPages > 1" class="contribution-page-navigation">
        <button
          type="button"
          :disabled="loading || result.page <= 1"
          @click="state.load(result.page - 1)"
        >
          上一页
        </button>
        <span class="contribution-page-number"
          >第 {{ result.page }} / {{ result.totalPages }} 页</span
        >
        <button
          type="button"
          :disabled="loading || result.page >= result.totalPages"
          @click="state.load(result.page + 1)"
        >
          下一页
        </button>
      </div>
    </nav>
    <dialog
      ref="dialog"
      class="contribution-dialog"
      aria-labelledby="contribution-title"
      @cancel.prevent="close"
      @close="onDialogClosed"
    >
      <header class="contribution-dialog-header">
        <h2 id="contribution-title">申请详情</h2>
        <button
          type="button"
          aria-label="关闭申请详情"
          :disabled="state.withdrawing.value"
          @click="close"
        >
          ×
        </button>
      </header>
      <p v-if="detailLoading" class="contribution-status" role="status">正在读取申请详情…</p>
      <div v-else-if="detailError" class="contribution-error" role="alert">
        <p>{{ detailError }}</p>
        <button type="button" @click="selectedId && state.select(selectedId)">重试详情</button>
      </div>
      <div v-else-if="detail" :key="detail.application.id" class="contribution-detail-content">
        <div class="contribution-dialog-body">
          <div class="contribution-detail-summary">
            <span class="contribution-badge" :data-status="detail.application.status">{{
              resultName(detail.application)
            }}</span
            ><span>{{
              detail.application.requestType === 'CREATE' ? '新店投稿' : '资料纠错'
            }}</span>
            <h3>{{ submittedName }}</h3>
            <p>提交于 {{ listTime(detail.application.createdAt) }}</p>
          </div>
          <div v-if="detail.application.status === 'APPROVED'" class="contribution-outcome">
            <span aria-hidden="true" class="contribution-outcome-icon">✓</span>
            <div v-if="detail.application.approvalMode === 'LINKED_EXISTING'">
              <strong>店铺已收录</strong>
            </div>
            <strong v-else>{{
              detail.application.requestType === 'CREATE'
                ? '已按审核结果新增店铺'
                : '已按审核结果更新店铺资料'
            }}</strong>
          </div>
          <p v-else-if="detail.application.status === 'PENDING'" class="contribution-result">
            申请正在等待审核，通过后店铺资料会公开生效。
          </p>
          <p v-else-if="detail.application.status === 'WITHDRAWN'" class="contribution-result">
            本次申请已撤回，不能继续编辑或重提；内容仍为你保留。
          </p>
          <div v-if="detail.application.status === 'REJECTED'" class="contribution-rejection">
            <h3>驳回原因</h3>
            <p>{{ detail.rejectReason }}</p>
          </div>
          <section class="contribution-detail-section contribution-submitted">
            <h3>我提交的资料</h3>
            <MyContributionSnapshot :values="detail.proposed" />
            <details v-if="changes.length" class="contribution-changes">
              <summary>
                <span>管理员修改了 {{ changes.length }} 项</span
                ><span class="change-expand">查看修改</span
                ><span class="change-collapse">收起修改</span>
              </summary>
              <div class="contribution-changes-body">
                <section v-for="change in changes" :key="change.key" class="contribution-change">
                  <h4>{{ change.label }}</h4>
                  <dl>
                    <dt>我提交的</dt>
                    <dd>{{ change.before }}</dd>
                    <dt>修改后</dt>
                    <dd class="change-after">{{ change.after }}</dd>
                  </dl>
                </section>
              </div>
            </details>
          </section>
          <section
            v-if="photos.length"
            class="contribution-detail-section contribution-submitted-photos"
          >
            <h3>
              提交时的附图 <span>{{ photos.length }} 张</span>
            </h3>
            <div class="contribution-photos">
              <div v-for="photo in photos" :key="photo.position" class="contribution-photo">
                <span v-if="photo.loading" role="status">图片加载中…</span>
                <div v-else-if="photo.error" class="contribution-photo-error">
                  <span>{{ photo.error }}</span
                  ><button type="button" @click="state.loadPhoto(photo.position)">
                    重试图片 {{ photo.position + 1 }}
                  </button>
                </div>
                <button
                  v-else-if="photo.src"
                  type="button"
                  :aria-label="`查看附图 ${photo.position + 1}`"
                  @click="viewPhoto(photo.src)"
                >
                  <img
                    :src="photo.src"
                    :alt="`申请附图 ${photo.position + 1}`"
                    @error="state.imageFailed(photo.position)"
                  />
                </button>
              </div>
            </div>
          </section>
          <section
            v-if="detail.reviewSubmission"
            class="contribution-detail-section attached-detail"
            :class="{ 'attached-published': detail.reviewSubmission.status === 'PUBLISHED' }"
          >
            <h3>
              附带点评
              <span class="contribution-badge">{{
                detail.reviewSubmission.status === 'PUBLISHED'
                  ? '已发布'
                  : detail.reviewSubmission.status === 'WITHDRAWN'
                    ? '已撤回'
                    : '待发布'
              }}</span>
            </h3>
            <template v-if="detail.reviewSubmission.status === 'PUBLISHED'">
              <p class="contribution-published-note">
                {{
                  detail.targetAvailable
                    ? '点评已发布，可前往店铺查看。'
                    : '点评已发布，店铺暂不可访问。'
                }}
              </p>
            </template>
            <template v-else>
              <p class="contribution-section-note">仅你自己可查看，尚未公开。</p>
              <div v-if="detail.reviewSubmission.scores" class="attached-scores">
                <span
                  v-for="(label, key) in {
                    taste: '口味',
                    value: '性价比',
                    service: '服务',
                    ambience: '环境',
                  }"
                  :key="key"
                  >{{ label }} <b>{{ detail.reviewSubmission.scores[key] / 2 }}</b></span
                >
              </div>
              <p v-if="detail.reviewSubmission.recommendation" class="attached-attitude">
                {{ recommendationLabels[detail.reviewSubmission.recommendation] }}
              </p>
              <p class="attached-body">{{ detail.reviewSubmission.body }}</p>
              <div class="contribution-photos">
                <div
                  v-for="photo in state.reviewPhotos.value"
                  :key="photo.position"
                  class="contribution-photo"
                >
                  <span v-if="photo.loading" role="status">点评图片加载中…</span>
                  <div v-else-if="photo.error" class="contribution-photo-error">
                    <span>{{ photo.error }}</span
                    ><button type="button" @click="state.loadPhoto(photo.position, true)">
                      重试点评图片 {{ photo.position + 1 }}
                    </button>
                  </div>
                  <button
                    v-else-if="photo.src"
                    type="button"
                    :aria-label="`查看点评图片${photo.position + 1}`"
                    @click="viewPhoto(photo.src)"
                  >
                    <img
                      :src="photo.src"
                      :alt="`点评图片${photo.position + 1}`"
                      @error="state.imageFailed(photo.position, true)"
                    />
                  </button>
                </div>
              </div>
            </template>
          </section>
          <details class="contribution-application-info">
            <summary>申请信息</summary>
            <dl>
              <dt>申请编号</dt>
              <dd>{{ detail.application.id }}</dd>
              <dt>提交时间</dt>
              <dd>{{ time(detail.application.createdAt) }}</dd>
              <template v-if="detail.application.reviewedAt"
                ><dt>审核时间</dt>
                <dd>{{ time(detail.application.reviewedAt) }}</dd></template
              ><template v-if="detail.application.withdrawnAt"
                ><dt>撤回时间</dt>
                <dd>{{ time(detail.application.withdrawnAt) }}</dd></template
              >
            </dl>
          </details>
        </div>
        <footer class="contribution-detail-footer">
          <p v-if="detail.resubmittedRequestId" class="contribution-result">
            这条申请已重新提交，点评随新申请继续保留。<button
              type="button"
              @click="state.select(detail.resubmittedRequestId!)"
            >
              查看后续申请 ›
            </button>
          </p>
          <RouterLink
            v-if="
              detail.application.status === 'PENDING' ||
              (detail.application.status === 'REJECTED' && !detail.resubmittedRequestId)
            "
            class="contribution-edit"
            :to="`/account/contributions/${detail.application.id}/edit`"
            @click="close"
            >{{
              detail.application.status === 'REJECTED' ? '修改并重新提交' : '修改申请'
            }}</RouterLink
          >
          <button
            v-if="detail.application.status === 'PENDING'"
            type="button"
            :disabled="state.withdrawing.value"
            @click="askWithdraw"
          >
            撤回申请
          </button>
          <RouterLink
            v-if="detail.targetAvailable && detail.application.restaurantId"
            class="contribution-visit"
            :to="`/restaurants/${detail.application.restaurantId}`"
            @click="close"
            >{{
              detail.reviewSubmission?.status === 'PUBLISHED' ? '查看店铺及点评 ›' : '查看店铺 ›'
            }}</RouterLink
          >
          <p v-else-if="detail.application.restaurantId">关联店铺已失效，申请记录仍然保留。</p>
        </footer>
      </div>
    </dialog>
    <dialog
      ref="withdrawDialog"
      class="withdraw-dialog"
      aria-labelledby="withdraw-title"
      @cancel="state.withdrawing.value && $event.preventDefault()"
    >
      <h3 id="withdraw-title">撤回这次申请？</h3>
      <p>店铺和附带点评都不会发布。撤回后仍可查看内容，但不能继续编辑或重提。</p>
      <p v-if="state.mutationError.value" role="alert" class="contribution-error">
        {{ state.mutationError.value }}
      </p>
      <div>
        <button type="button" :disabled="state.withdrawing.value" @click="withdrawDialog?.close()">
          继续等待审核</button
        ><button type="button" :disabled="state.withdrawing.value" @click="state.withdraw">
          {{ state.withdrawing.value ? '正在撤回…' : '确认撤回' }}
        </button>
      </div>
    </dialog>
    <dialog
      ref="lightbox"
      class="contribution-lightbox"
      aria-label="申请附图大图"
      @close="preview = ''"
    >
      <button type="button" aria-label="关闭附图大图" @click="lightbox?.close()">×</button
      ><img v-if="preview" :src="preview" alt="申请附图大图" />
    </dialog>
  </section>
</template>
<style scoped src="./my-contributions.css"></style>
