<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useLoginStore } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'
import { useMyRestaurantRequests } from '../model/useMyRestaurantRequests'
import { myRequestStatusNames, type MyRequestFilter } from '../api/myRestaurantRequestsApi'
import MyContributionSnapshot from './MyContributionSnapshot.vue'

/** 本人投稿入口只编排真实读取，不提供本轮尚未开放的修改、撤回或重提操作。 */
const state = useMyRestaurantRequests()
const route = useRoute()
const auth = useLoginStore()
let openedTarget = ''
const { filter, result, loading, error, selectedId, detail, detailLoading, detailError, photos } =
  state
const dialog = ref<globalThis.HTMLDialogElement>(),
  lightbox = ref<globalThis.HTMLDialogElement>(),
  preview = ref('')
const filters: MyRequestFilter[] = ['ALL', 'PENDING', 'APPROVED', 'REJECTED']
const time = (value: string | null) =>
  value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '尚未审核'
function open(id: string) {
  void state.select(id)
  dialog.value?.showModal()
}
function close() {
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
              myRequestStatusNames[item.status]
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
    <p class="contribution-private-note">申请记录仅你和有权处理的管理员可见。</p>
    <dialog
      ref="dialog"
      class="contribution-dialog"
      aria-labelledby="contribution-title"
      @cancel.prevent="close"
      @close="onDialogClosed"
    >
      <header class="contribution-dialog-header">
        <h2 id="contribution-title">申请详情</h2>
        <button type="button" aria-label="关闭申请详情" @click="close">×</button>
      </header>
      <p v-if="detailLoading" class="contribution-status" role="status">正在读取申请详情…</p>
      <div v-else-if="detailError" class="contribution-error" role="alert">
        <p>{{ detailError }}</p>
        <button type="button" @click="selectedId && state.select(selectedId)">重试详情</button>
      </div>
      <template v-else-if="detail">
        <div class="contribution-detail-summary">
          <span class="contribution-badge" :data-status="detail.application.status">{{
            myRequestStatusNames[detail.application.status]
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
          <h3>审核通过的内容</h3>
          <p class="contribution-section-note">这是审核时确认的资料，可能包含管理员修订。</p>
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
        <footer class="contribution-detail-footer">
          <RouterLink
            v-if="detail.targetAvailable && detail.application.restaurantId"
            :to="`/restaurants/${detail.application.restaurantId}`"
            @click="close"
            >查看店铺 ›</RouterLink
          >
          <p v-else-if="detail.application.restaurantId">关联店铺已失效，申请记录仍然保留。</p>
          <p>当前可查看记录；修改、撤回和驳回重提暂未开放。</p>
        </footer>
      </template>
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
