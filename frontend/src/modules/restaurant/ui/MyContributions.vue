<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useLoginStore } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'
import { useMyRestaurantRequests } from '../model/useMyRestaurantRequests'
import { myRequestStatusNames, type MyRequestFilter } from '../api/myRestaurantRequestsApi'
import MyContributionSnapshot from './MyContributionSnapshot.vue'
import { recommendationLabels } from '@/modules/review/model/review'

/** 本人详情展示私有点评，并按服务端状态开放修改、撤回、重提与后续申请入口。 */
const state = useMyRestaurantRequests()
const route = useRoute()
const auth = useLoginStore()
let openedTarget = ''
const { filter, result, loading, error, selectedId, detail, detailLoading, detailError, photos } =
  state
const dialog = ref<globalThis.HTMLDialogElement>(),
  withdrawDialog = ref<globalThis.HTMLDialogElement>(),
  lightbox = ref<globalThis.HTMLDialogElement>(),
  preview = ref('')
const filters: MyRequestFilter[] = ['ALL', 'PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN']
const time = (value: string | null) =>
  value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '尚未审核'
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
    <header class="contributions-toolbar">
      <p>你的每一次补充，都让地图更完整。</p>
      <button type="button" :disabled="loading" @click="state.load(1)">刷新</button>
      <RouterLink class="contribution-submit" to="/restaurants/submit">提交新店 ›</RouterLink>
    </header>
    <p v-if="state.mutationMessage.value" class="contribution-result" role="status">
      {{ state.mutationMessage.value }}
    </p>
    <div class="contributions-filters" role="group" aria-label="申请状态筛选">
      <button
        v-for="status in filters"
        :key="status"
        type="button"
        :aria-pressed="filter === status"
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
            ><span class="contribution-type">{{
              item.requestType === 'CREATE' ? '新店投稿' : '资料纠错'
            }}</span
            ><span class="contribution-badge" :data-status="item.status">{{
              item.approvalMode === 'LINKED_EXISTING'
                ? '店铺已收录'
                : myRequestStatusNames[item.status]
            }}</span></span
          >
          <strong>{{ item.name }}</strong>
          <span class="contribution-meta"
            ><span>提交于 {{ time(item.createdAt) }}</span
            ><span v-if="item.photoCount">{{ item.photoCount }} 张附图</span></span
          >
          <span class="contribution-card-footer"
            ><span>申请编号 {{ item.id }}</span
            ><span>查看详情 ›</span></span
          >
        </button>
      </li>
    </ul>
    <nav
      v-if="result && result.totalPages > 1"
      class="contribution-pagination"
      aria-label="申请记录分页"
    >
      <button type="button" :disabled="result.page <= 1" @click="state.load(result.page - 1)">
        上一页
      </button>
      <span>第 {{ result.page }} / {{ result.totalPages }} 页 · {{ result.total }} 条</span>
      <button
        type="button"
        :disabled="result.page >= result.totalPages"
        @click="state.load(result.page + 1)"
      >
        下一页
      </button>
    </nav>
    <p class="contribution-private-note">
      店铺申请仅你和有权处理的管理员可见；待发布点评仅你自己可查看。
    </p>
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
      <template v-else-if="detail">
        <div class="contribution-detail-summary">
          <span class="contribution-badge" :data-status="detail.application.status">{{
            detail.application.approvalMode === 'LINKED_EXISTING'
              ? '店铺已收录'
              : myRequestStatusNames[detail.application.status]
          }}</span
          ><span>{{ detail.application.requestType === 'CREATE' ? '新店投稿' : '资料纠错' }}</span>
          <h3>{{ detail.application.name }}</h3>
          <p>申请编号 {{ detail.application.id }}</p>
          <p>提交时间 {{ time(detail.application.createdAt) }}</p>
          <p v-if="detail.application.reviewedAt">
            审核时间 {{ time(detail.application.reviewedAt) }}
          </p>
        </div>
        <p v-if="detail.application.status === 'PENDING'" class="contribution-result">
          申请正在等待审核，通过后店铺资料会公开生效。
        </p>
        <div v-if="detail.application.status === 'REJECTED'" class="contribution-rejection">
          <h3>驳回原因</h3>
          <p>{{ detail.rejectReason }}</p>
        </div>
        <section v-if="detail.approved" class="contribution-detail-section">
          <h3>
            {{
              detail.application.approvalMode === 'LINKED_EXISTING'
                ? '关联的已收录店铺'
                : '审核通过的内容'
            }}
          </h3>
          <p class="contribution-section-note">
            {{
              detail.application.approvalMode === 'LINKED_EXISTING'
                ? '管理员确认该店铺已收录，本次申请已结束。下方是处理时的店铺资料。'
                : '这是审核时确认的资料，可能包含管理员修订。'
            }}
          </p>
          <p v-if="detail.reviewSubmission?.status === 'PUBLISHED'" class="contribution-result">
            附带点评已自动发布到关联店铺。
          </p>
          <MyContributionSnapshot :values="detail.approved" />
        </section>
        <section class="contribution-detail-section">
          <h3>我提交的内容</h3>
          <MyContributionSnapshot :values="detail.proposed" />
        </section>
        <section class="contribution-detail-section">
          <h3>
            提交时的附图 <span>{{ photos.length }} 张</span>
          </h3>
          <p v-if="!photos.length" class="contribution-section-note">本次申请没有附图。</p>
          <div v-else class="contribution-photos">
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
        <section v-if="detail.reviewSubmission" class="contribution-detail-section attached-detail">
          <h3>
            我附带的点评
            <span class="contribution-badge">{{
              detail.reviewSubmission.status === 'PUBLISHED'
                ? '已发布'
                : detail.reviewSubmission.status === 'WITHDRAWN'
                  ? '已撤回'
                  : '待发布'
            }}</span>
          </h3>
          <template v-if="detail.reviewSubmission.status === 'PUBLISHED'">
            <p class="contribution-result">点评已自动发布，发布回执已保存。</p>
            <RouterLink
              v-if="detail.targetAvailable && detail.application.restaurantId"
              :to="`/restaurants/${detail.application.restaurantId}`"
              @click="close"
              >前往店铺查看点评 ›</RouterLink
            >
            <p class="contribution-section-note">若点评后来被删除，发布回执仍会保留。</p>
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
            :to="`/restaurants/${detail.application.restaurantId}`"
            @click="close"
            >查看店铺 ›</RouterLink
          >
          <p v-else-if="detail.application.restaurantId">关联店铺已失效，申请记录仍然保留。</p>
          <p v-if="detail.application.status === 'WITHDRAWN'">
            本次申请已撤回，不能继续编辑或重提；内容仍为你保留。
          </p>
        </footer>
      </template>
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
