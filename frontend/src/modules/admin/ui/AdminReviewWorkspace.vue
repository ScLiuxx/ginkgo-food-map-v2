<script setup lang="ts">
import { computed, onBeforeUnmount, ref, useTemplateRef, watch } from 'vue'
import {
  useRestaurantRequests,
  requestStatusNames,
  formatRequestTime,
} from '../model/useRestaurantRequests'
import type { RequestStatus } from '../api/restaurantRequestsApi'
import ReviewInlineField from './ReviewInlineField.vue'
import AdminSidebar from './AdminSidebar.vue'
import LinkExistingRestaurantDialog from './LinkExistingRestaurantDialog.vue'

// 身份由页面编排传入；真实查询只在管理会话校验通过后挂载，不跨模块读取认证Store。
defineProps<{
  username: string
  loggingOut: boolean
  sessionError: string | null
}>()
defineEmits<{ logout: [] }>()
const {
  draft,
  categories,
  categoryError,
  loadCategories,
  submitting,
  continuous,
  decisionError,
  notice,
  rejectOpen,
  editField,
  decide,
  tab,
  query,
  page,
  items,
  selectedId,
  selected,
  detail,
  total,
  totalPages,
  counts,
  loadingList,
  loadingDetail,
  listError,
  detailError,
  photos,
  comparison,
  reload,
  selectRequest,
  loadPhoto,
} = useRestaurantRequests()
const statuses: RequestStatus[] = ['PENDING', 'APPROVED', 'REJECTED']
const linkOpen = ref(false)
/** 打开独立核对窗口，清除上一轮操作错误，不带入资料编辑草稿。 */
function openLinkExisting() {
  decisionError.value = ''
  rejectOpen.value = false
  linkOpen.value = true
}
/** 选择窗口绑定当前申请；切换记录或服务端确认终态后立即销毁，清空搜索和选择。 */
watch(
  () => [selectedId.value, detail.value?.application.status],
  () => {
    linkOpen.value = false
  },
)
const changedCount = computed(() => comparison.value.filter((row) => row.changed).length)
/** 新店没有旧资料可比较；已收录记录仍须核对原投稿和关联店铺，不能误示为资料覆盖。 */
const singleColumn = computed(
  () =>
    selected.value?.requestType === 'CREATE' && selected.value.approvalMode !== 'LINKED_EXISTING',
)
const statusLabel = computed(() =>
  selected.value?.approvalMode === 'LINKED_EXISTING'
    ? '店铺已收录'
    : selected.value
      ? requestStatusNames[selected.value.status]
      : '',
)
const photoDialog = useTemplateRef('photoDialog')
const previewSrc = ref(''),
  previewTitle = ref('')
/** 仅展示当前已认证请求取得的Blob；切换申请时关闭大图并由查询模型撤销旧URL。 */
function openPhoto(src: string, position: number) {
  if (!src) return
  previewSrc.value = src
  previewTitle.value = `申请图片 ${position + 1}`
  photoDialog.value?.showModal()
}
watch(selectedId, () => {
  photoDialog.value?.close()
  previewSrc.value = ''
})
onBeforeUnmount(() => photoDialog.value?.close())
</script>

<template>
  <div class="admin-review-preview admin-theme">
    <header class="topbar">
      <RouterLink class="brand" to="/admin/reviews"><em>泥</em><span>泥电管理端</span></RouterLink>
      <div class="account">
        <span class="identity"
          ><span class="avatar">管</span
          ><span class="admin-username" :title="username">{{ username }}</span></span
        ><button class="admin-logout" :disabled="loggingOut" @click="$emit('logout')">
          {{ loggingOut ? '正在退出…' : '退出登录' }}
        </button>
      </div>
    </header>
    <p v-if="sessionError" class="session-error" role="alert">
      {{ sessionError }}，可重新点击退出登录重试。
    </p>
    <div class="shell">
      <AdminSidebar :pending-count="loadingList ? '…' : counts.PENDING" />
      <main>
        <div class="page-head">
          <h1>商户审核</h1>
          <label class="search">
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.7"
              aria-hidden="true"
            >
              <circle cx="10.5" cy="10.5" r="6.5" />
              <path d="m16 16 5 5" />
            </svg>
            <input
              v-model="query"
              type="search"
              aria-label="搜索申请"
              placeholder="搜索店名或申请编号"
              maxlength="80"
              :disabled="submitting"
            />
          </label>
        </div>
        <div class="toolbar">
          <div class="tabs" aria-label="申请状态筛选">
            <button
              v-for="status in statuses"
              :key="status"
              class="tab"
              :class="{ active: tab === status }"
              :aria-pressed="tab === status"
              :disabled="submitting"
              @click="tab = status"
            >
              {{ requestStatusNames[status]
              }}<span v-if="status === 'PENDING'">{{ loadingList ? '…' : counts[status] }}</span>
            </button>
          </div>
        </div>
        <p v-if="notice" class="decision-notice" role="status">{{ notice }}</p>
        <div class="workspace">
          <section class="queue" aria-label="申请列表" :aria-busy="loadingList">
            <div class="queue-title">
              <span aria-live="polite"
                >{{ requestStatusNames[tab] }} · {{ loadingList ? '…' : total }} 条</span
              ><span>最早提交优先</span>
            </div>
            <div v-if="loadingList" class="empty" role="status">正在加载申请…</div>
            <div v-else-if="listError" class="query-error" role="alert">
              <p>{{ listError }}</p>
              <button class="btn" @click="reload()">重试列表</button>
            </div>
            <div v-else class="request-list">
              <button
                v-for="item in items"
                :key="item.id"
                class="request"
                :class="{ selected: selectedId === item.id }"
                :aria-pressed="selectedId === item.id"
                :disabled="submitting"
                @click="selectRequest(item.id)"
              >
                <strong>{{ item.name }}</strong>
                <span class="request-summary"
                  ><span class="pill" :class="{ new: item.requestType === 'CREATE' }">{{
                    item.requestType === 'CREATE' ? '新店投稿' : '资料纠错'
                  }}</span
                  ><span class="request-author">{{ item.submitterName }}</span>
                  <span v-if="item.approvalMode === 'LINKED_EXISTING'" class="linked-status"
                    >店铺已收录</span
                  >
                </span>
                <time class="request-time" :datetime="item.createdAt">{{
                  formatRequestTime(item.createdAt)
                }}</time>
              </button>
              <p v-if="!items.length" class="empty">
                {{ query.trim() ? '没有匹配的申请' : '暂无' + requestStatusNames[tab] + '申请' }}
              </p>
            </div>
            <div v-if="!loadingList && !listError && items.length" class="queue-footer">
              <span>共 {{ total }} 条</span>
              <div v-if="totalPages > 1" class="queue-pagination">
                <button
                  class="btn"
                  aria-label="上一页"
                  :disabled="submitting || loadingList || page <= 1"
                  @click="page--"
                >
                  <svg
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="1.7"
                    aria-hidden="true"
                  >
                    <path d="m14 5-7 7 7 7" />
                  </svg></button
                ><span>{{ page }} / {{ totalPages }}</span
                ><button
                  class="btn"
                  aria-label="下一页"
                  :disabled="submitting || loadingList || page >= totalPages"
                  @click="page++"
                >
                  <svg
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="1.7"
                    aria-hidden="true"
                  >
                    <path d="m9 5 7 7-7 7" />
                  </svg>
                </button>
              </div>
            </div>
          </section>
          <article v-if="selected" class="detail" aria-label="审核详情" :aria-busy="loadingDetail">
            <div class="detail-content" :class="{ 'is-loading': loadingDetail }">
              <div class="detail-heading">
                <h2>{{ selected.name }}</h2>
                <span class="status-badge" :class="selected.status.toLowerCase()">{{
                  statusLabel
                }}</span>
              </div>
              <div class="meta">
                <span>{{ selected.requestType === 'CREATE' ? '新店投稿' : '资料纠错' }}</span>
                <span>{{ selected.submitterName }}</span
                ><span>{{ formatRequestTime(selected.createdAt) }}</span
                ><span>#{{ selected.id }}</span>
              </div>
              <div v-if="detailError" class="query-error" role="alert">
                <p>{{ detailError }}</p>
                <button class="btn" @click="selectRequest(selected.id)">重试详情</button>
              </div>
              <template v-if="detail && !detailError">
                <div v-if="detail.targetDeleted || detail.rejectReason" class="intro" role="status">
                  <span v-if="detail.targetDeleted">目标商户已删除；当前资料仅供核对。</span>
                  <span v-if="detail.rejectReason">驳回原因：{{ detail.rejectReason }}</span>
                </div>
                <div v-if="detail.reviewedAt" class="meta review-meta">
                  审核人 #{{ detail.reviewedBy }} ·
                  {{ formatRequestTime(detail.reviewedAt) }}
                  <RouterLink
                    v-if="selected.restaurantId && !detail.targetDeleted"
                    :to="`/admin/restaurants/${selected.restaurantId}/edit`"
                    >查看店铺资料 ↗</RouterLink
                  >
                </div>
                <div class="section-head">
                  <h3>
                    {{
                      singleColumn
                        ? selected.status === 'APPROVED'
                          ? '通过资料'
                          : '店铺资料'
                        : '资料核对'
                    }}
                  </h3>
                  <span v-if="!singleColumn"
                    ><i class="legend" />{{
                      selected.approvalMode === 'LINKED_EXISTING'
                        ? '投稿资料与关联店铺的差异，不代表资料更新'
                        : `${changedCount} 项变更`
                    }}</span
                  >
                </div>
                <table
                  class="comparison"
                  :class="{ 'single-column': singleColumn }"
                  :aria-label="singleColumn ? '店铺资料' : '资料对照'"
                >
                  <thead v-if="!singleColumn">
                    <tr>
                      <th scope="col">资料项</th>
                      <th scope="col">
                        {{
                          selected.approvalMode === 'LINKED_EXISTING'
                            ? '原投稿资料'
                            : selected.status === 'APPROVED'
                              ? '审核前资料'
                              : '当前商户资料'
                        }}
                      </th>
                      <th scope="col">
                        {{
                          selected.approvalMode === 'LINKED_EXISTING'
                            ? '关联店铺资料（处理时）'
                            : selected.status === 'APPROVED'
                              ? '最终通过资料'
                              : '本次提交'
                        }}
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="row in comparison" :key="`${selected.id}-${row.key}`">
                      <th scope="row">{{ row.label }}</th>
                      <td
                        v-if="!singleColumn"
                        :class="{
                          'old-value': row.changed && detail.current !== null,
                        }"
                      >
                        {{ row.before }}
                      </td>
                      <td class="field-value" :class="{ changed: row.changed && !singleColumn }">
                        <template v-if="row.key && selected.status === 'PENDING'">
                          <div v-if="row.key === 'position'" class="coordinate-edit">
                            <ReviewInlineField
                              field="latitude"
                              :value="draft.latitude"
                              :text="`纬度 ${draft.latitude ?? '未填写'}`"
                              :categories="categories"
                              :disabled="submitting"
                              @change="editField('latitude', $event)"
                            />
                            <ReviewInlineField
                              field="longitude"
                              :value="draft.longitude"
                              :text="`经度 ${draft.longitude ?? '未填写'}`"
                              :categories="categories"
                              :disabled="submitting"
                              @change="editField('longitude', $event)"
                            />
                          </div>
                          <ReviewInlineField
                            v-else
                            :field="row.key"
                            :value="draft[row.key]"
                            :text="row.after"
                            :categories="categories"
                            :disabled="
                              submitting ||
                              (row.key === 'categoryCode' && draft.restaurantType === 'CANTEEN')
                            "
                            @change="editField(row.key, $event)"
                          />
                        </template>
                        <template v-else>{{ row.after }}</template>
                      </td>
                    </tr>
                  </tbody>
                </table>
                <p v-if="categoryError" class="photo-note" role="alert">
                  分类选项加载失败：{{ categoryError }}
                  <button class="btn" @click="loadCategories">重试分类</button>
                </p>
                <section class="photos-section">
                  <div class="section-head">
                    <h3>
                      提交的图片
                      <span class="muted small">/ {{ photos.length }} 张</span>
                    </h3>
                    <span v-if="photos.length">{{
                      selected.status === 'PENDING'
                        ? '通过后按顺序加入商户相册'
                        : selected.status === 'REJECTED'
                          ? '已驳回，图片仅供审核核对'
                          : selected.approvalMode === 'LINKED_EXISTING'
                            ? '已关联已有店铺，投稿附图保留供核对'
                            : detail.publishedPhotos?.length
                              ? `本次已发布 ${detail.publishedPhotos.length} 张`
                              : '历史申请图片未发布到相册'
                    }}</span>
                  </div>
                  <p v-if="!photos.length" class="photo-note">本次申请未附图片。</p>
                  <div v-else class="photos real-photos">
                    <div v-for="photo in photos" :key="photo.position" class="photo-slot">
                      <button
                        v-if="photo.src && !photo.error"
                        class="photo real-photo"
                        :aria-label="`查看申请图片 ${photo.position + 1}`"
                        @click="openPhoto(photo.src, photo.position)"
                      >
                        <img
                          :src="photo.src"
                          :alt="`申请图片 ${photo.position + 1}`"
                          @error="photo.error = '图片无法显示，请重试'"
                        />
                      </button>
                      <div v-else class="photo">
                        <span v-if="photo.loading">图片加载中…</span
                        ><template v-else
                          ><span>图片加载失败</span
                          ><button
                            class="photo-retry"
                            :aria-label="`重试图片 ${photo.position + 1}`"
                            @click="loadPhoto(photo.position)"
                          >
                            重试
                          </button></template
                        >
                      </div>
                    </div>
                  </div>
                </section>
                <p v-if="detail.publishedPhotos?.length" class="photo-note">
                  本次相册发布记录：<template
                    v-for="photo in detail.publishedPhotos"
                    :key="photo.id"
                  >
                    <span v-if="detail.targetDeleted"
                      >照片 {{ photo.position + 1 }}（商户已删除）
                    </span>
                    <a v-else :href="photo.url" target="_blank" rel="noopener noreferrer"
                      >照片 {{ photo.position + 1 }}{{ photo.cover ? '（封面）' : '' }} ↗
                    </a>
                  </template>
                </p>
              </template>
            </div>
            <div v-if="loadingDetail" class="detail-loading" role="status">
              <span class="loading-spinner" />正在加载申请详情…
            </div>
            <div class="review-actions">
              <div v-if="selected.status !== 'PENDING'" class="action-note" aria-live="polite">
                {{
                  selected.approvalMode === 'LINKED_EXISTING'
                    ? `已关联已有店铺 #${selected.restaurantId}，申请已结束。`
                    : '此申请已处理，不能再次变更审核结果。'
                }}
              </div>
              <div v-if="selected.status === 'PENDING'" class="decision-controls">
                <label class="continuous-toggle"
                  ><input
                    v-model="continuous"
                    type="checkbox"
                    :disabled="submitting"
                  />自动加载下一条</label
                >
                <p v-if="decisionError" class="decision-error" role="alert">
                  {{ decisionError }}
                </p>
                <div class="buttons">
                  <button
                    v-if="selected.requestType === 'CREATE'"
                    class="btn secondary"
                    :disabled="submitting || loadingList || !detail || loadingDetail"
                    @click="openLinkExisting"
                  >
                    店铺已收录
                  </button>
                  <div class="reject-control">
                    <button
                      class="btn reject-button"
                      :disabled="submitting || loadingList || !detail || loadingDetail"
                      :aria-expanded="rejectOpen"
                      @click="rejectOpen = !rejectOpen"
                    >
                      驳回
                    </button>
                    <template v-if="rejectOpen">
                      <button
                        class="popover-dismiss"
                        aria-label="关闭驳回原因"
                        @click="rejectOpen = false"
                      />
                      <div
                        class="reject-popover"
                        role="dialog"
                        aria-label="选择驳回原因"
                        @keydown.esc="rejectOpen = false"
                      >
                        <div class="popover-title">
                          选择驳回原因<button aria-label="关闭" @click="rejectOpen = false">
                            ×
                          </button>
                        </div>
                        <p>点击原因即提交驳回，原始资料与图片保留供核对。</p>
                        <div class="reason-tags">
                          <button
                            v-for="reason in ['图片模糊', '无效地址', '信息不实']"
                            :key="reason"
                            :disabled="submitting"
                            @click="decide('reject', reason)"
                          >
                            {{ reason }}
                          </button>
                        </div>
                      </div>
                    </template>
                  </div>
                  <button
                    class="btn primary"
                    :disabled="
                      submitting || loadingList || !detail || loadingDetail || detail.targetDeleted
                    "
                    @click="decide('approve')"
                  >
                    {{ submitting ? '正在提交…' : '审核通过' }}
                  </button>
                </div>
              </div>
              <p v-else-if="decisionError" class="decision-error" role="alert">
                {{ decisionError }}
              </p>
            </div>
          </article>
          <div v-else class="detail empty">
            <h2>
              {{
                loadingList ? '正在加载申请' : listError ? '暂时无法读取申请' : '暂无可查看的申请'
              }}
            </h2>
            <p>
              {{
                loadingList
                  ? '稍候即可查看资料与图片。'
                  : listError
                    ? '请在左侧重试列表。'
                    : '选择其他状态或调整搜索条件。'
              }}
            </p>
          </div>
        </div>
      </main>
    </div>
    <dialog ref="photoDialog" class="photo-viewer" aria-labelledby="admin-photo-title">
      <h2 id="admin-photo-title">{{ previewTitle }}</h2>
      <img v-if="previewSrc" :src="previewSrc" :alt="previewTitle" />
      <div class="buttons">
        <button class="btn" @click="photoDialog?.close()">关闭</button>
      </div>
    </dialog>
    <LinkExistingRestaurantDialog
      v-if="linkOpen && detail && selected?.status === 'PENDING'"
      :key="selectedId ?? ''"
      :restaurant-name="String(detail.proposed.name ?? '')"
      :restaurant-type="detail.proposed.restaurantType === 'CANTEEN' ? 'CANTEEN' : 'REGULAR'"
      :submitting="submitting"
      :error="decisionError"
      @close="linkOpen = false"
      @confirm="decide('link-existing', $event)"
    />
  </div>
</template>

<style scoped src="./admin-review.css"></style>
