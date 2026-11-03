<script setup lang="ts">
import { computed, onBeforeUnmount, ref, useTemplateRef, watch } from 'vue'
import {
  useRestaurantRequests,
  requestStatusNames,
  formatRequestTime,
} from '../model/useRestaurantRequests'
import type { RequestStatus } from '../api/restaurantRequestsApi'
import ReviewInlineField from './ReviewInlineField.vue'

// 身份由页面编排传入；真实查询只在管理会话校验通过后挂载，不跨模块读取认证Store。
defineProps<{ username: string; loggingOut: boolean; sessionError: string | null }>()
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
const changedCount = computed(() => comparison.value.filter((row) => row.changed).length)
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
  <div class="admin-review-preview">
    <!-- 图标使用本地 SVG，保留设计稿形状，无外部资源或真实图片请求。 -->
    <svg style="display: none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <defs>
        <symbol id="admin-list" viewBox="0 0 24 24">
          <rect x="5" y="4" width="14" height="17" rx="2" />
          <path d="M9 4V2h6v2M9 10h6M9 14h6M9 18h4" />
        </symbol>
        <symbol id="admin-shop" viewBox="0 0 24 24">
          <path
            d="M4 10v10h16V10M3 10l2-6h14l2 6M3 10c0 4 5 4 5 0 0 4 8 4 8 0 0 4 5 4 5 0M9 20v-6h6v6"
          />
        </symbol>
        <symbol id="admin-chat" viewBox="0 0 24 24">
          <path
            d="M5 4h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H9l-5 3v-3H3V6a2 2 0 0 1 2-2Z M7 9h10M7 13h7"
          />
        </symbol>
        <symbol id="admin-history" viewBox="0 0 24 24">
          <path d="M4 7a9 9 0 1 1-1 9M3 3v5h5M12 7v6l4 2" />
        </symbol>
        <symbol id="admin-photo" viewBox="0 0 24 24">
          <rect x="3" y="4" width="18" height="16" rx="2" />
          <circle cx="8" cy="9" r="1.5" />
          <path d="m4 18 6-6 4 4 3-3 4 4" />
        </symbol>
      </defs>
    </svg>
    <header class="topbar">
      <RouterLink class="brand" to="/admin/reviews"
        ><em>泥电</em>美食地图<span class="brand-label">管理工作台</span></RouterLink
      >
      <div class="account">
        <span class="preview">商户审核</span
        ><span class="identity"
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
      <aside class="sidebar" aria-label="管理导航">
        <p class="nav-label">内容管理</p>
        <RouterLink class="nav-item active" to="/admin/reviews" aria-current="page"
          ><svg fill="none" stroke="currentColor" aria-hidden="true"><use href="#admin-list" /></svg
          >商户审核<span class="count">{{ loadingList ? '…' : counts.PENDING }}</span></RouterLink
        >
        <button class="nav-item" disabled>
          <svg fill="none" stroke="currentColor" aria-hidden="true"><use href="#admin-shop" /></svg
          >商户管理<span class="later">后续</span>
        </button>
        <button class="nav-item" disabled>
          <svg fill="none" stroke="currentColor" aria-hidden="true"><use href="#admin-chat" /></svg
          >点评管理<span class="later">后续</span>
        </button>
        <button class="nav-item" disabled>
          <svg fill="none" stroke="currentColor" aria-hidden="true">
            <use href="#admin-history" /></svg
          >操作记录<span class="later">后续</span>
        </button>
        <div class="sidebar-bottom">一起维护校园美食地图<br />让每一份分享都有据可循。</div>
      </aside>
      <main>
        <div class="breadcrumb">管理工作台<span>/</span>商户审核</div>
        <div class="page-head">
          <div>
            <h1>商户审核</h1>
            <p>核对同学提交的资料，让可靠的商户信息出现在地图上。</p>
          </div>
          <div class="queue-count">
            <b>{{ loadingList ? '…' : counts.PENDING }}</b> 条待处理
          </div>
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
              {{ requestStatusNames[status] }}<span>{{ loadingList ? '…' : counts[status] }}</span>
            </button>
          </div>
          <label class="search"
            ><span aria-hidden="true">⌕</span
            ><input
              v-model="query"
              type="search"
              aria-label="搜索申请"
              placeholder="搜索商户名称 / 申请编号"
              maxlength="80"
              :disabled="submitting"
          /></label>
        </div>
        <p v-if="notice" class="decision-notice" role="status">{{ notice }}</p>
        <div class="workspace">
          <section class="queue" aria-label="申请列表" :aria-busy="loadingList">
            <div class="queue-title">
              <span aria-live="polite"
                >{{ requestStatusNames[tab] }} · {{ loadingList ? '…' : total }} 条</span
              ><span>最早提交优先 ↓</span>
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
                <span class="request-top"
                  ><span class="pill" :class="{ new: item.requestType === 'CREATE' }">{{
                    item.requestType === 'CREATE' ? '新店投稿' : '资料纠错'
                  }}</span
                  ><span class="muted request-time">{{
                    formatRequestTime(item.createdAt)
                  }}</span></span
                >
                <strong>{{ item.name }}</strong
                ><span class="request-description">申请 #{{ item.id }}</span
                ><span class="request-foot"
                  ><span>{{ item.submitterName }}</span
                  ><span>{{ item.photoCount }} 张图片 · →</span></span
                >
              </button>
              <p v-if="!items.length" class="empty">
                {{ query.trim() ? '没有匹配的申请' : '暂无' + requestStatusNames[tab] + '申请' }}
              </p>
            </div>
            <div v-if="totalPages > 1" class="queue-pagination">
              <button
                class="btn"
                :disabled="submitting || loadingList || page <= 1"
                @click="page--"
              >
                上一页</button
              ><span>{{ page }} / {{ totalPages }}</span
              ><button
                class="btn"
                :disabled="submitting || loadingList || page >= totalPages"
                @click="page++"
              >
                下一页
              </button>
            </div>
            <div v-if="!loadingList && !listError && items.length" class="queue-end">
              共 {{ total }} 条 · 本页 {{ items.length }} 条
              <button :disabled="submitting" @click="reload()">刷新</button>
            </div>
          </section>
          <article v-if="selected" class="detail" aria-label="审核详情" :aria-busy="loadingDetail">
            <div class="detail-content" :class="{ 'is-loading': loadingDetail }">
              <div class="detail-top">
                <span class="pill" :class="{ new: selected.requestType === 'CREATE' }">{{
                  selected.requestType === 'CREATE' ? '新店投稿' : '资料纠错'
                }}</span
                ><span class="small orange">· {{ requestStatusNames[selected.status] }}</span
                ><span class="request-id">申请 #{{ selected.id }}</span>
              </div>
              <h2>{{ selected.name }}</h2>
              <div class="meta">
                <span>提交人：{{ selected.submitterName }}</span
                ><span>{{ formatRequestTime(selected.createdAt) }}</span
                ><span>{{
                  selected.restaurantId ? `商户 #${selected.restaurantId}` : '新商户 · 尚未公开'
                }}</span>
              </div>
              <div v-if="detailError" class="query-error" role="alert">
                <p>{{ detailError }}</p>
                <button class="btn" @click="selectRequest(selected.id)">重试详情</button>
              </div>
              <template v-if="detail && !detailError">
                <div class="intro">
                  <span aria-hidden="true">ⓘ</span
                  ><span v-if="detail.targetDeleted">目标商户已删除；当前资料仅供管理员核对。</span
                  ><span v-else-if="selected.status === 'PENDING'"
                    >本次提交仅为待审申请，通过后才会更新公开资料。</span
                  ><span v-else
                    >处理状态：{{ requestStatusNames[selected.status]
                    }}{{ detail.rejectReason ? ' · ' + detail.rejectReason : '' }}</span
                  >
                </div>
                <div v-if="detail.reviewedAt" class="meta">
                  审核人 #{{ detail.reviewedBy }} · {{ formatRequestTime(detail.reviewedAt) }}
                </div>
                <div class="section-head">
                  <h3>资料核对</h3>
                  <span
                    ><i class="legend" />{{
                      selected.requestType === 'CREATE' && !detail.current
                        ? '新商户资料'
                        : `${changedCount} 项变更已标出`
                    }}</span
                  >
                </div>
                <table class="comparison">
                  <thead>
                    <tr>
                      <th scope="col">资料项</th>
                      <th scope="col">
                        {{ selected.status === 'APPROVED' ? '审核前资料' : '当前商户资料' }}
                      </th>
                      <th scope="col">
                        {{ selected.status === 'APPROVED' ? '最终通过资料' : '本次提交' }}
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="row in comparison" :key="row.label">
                      <td>{{ row.label }}</td>
                      <td :class="{ 'old-value': row.changed && detail.current !== null }">
                        {{ row.before }}
                      </td>
                      <td :class="{ changed: row.changed }">
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
                            :disabled="submitting"
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
                      提交的图片 <span class="muted small">/ {{ photos.length }} 张</span>
                    </h3>
                    <span v-if="photos.length">{{
                      selected.status === 'PENDING'
                        ? '通过后按顺序加入商户相册'
                        : selected.status === 'REJECTED'
                          ? '已驳回，图片仅供审核核对'
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
              <div class="action-note" aria-live="polite">
                {{
                  selected.status === 'PENDING'
                    ? '通过后发布资料与附图；纠错图片追加到原相册。'
                    : '此申请已处理，不能再次变更审核结果。'
                }}
              </div>
              <div v-if="selected.status === 'PENDING'" class="decision-controls">
                <label class="continuous-toggle"
                  ><input
                    v-model="continuous"
                    type="checkbox"
                    :disabled="submitting"
                  />审核后自动加载下一条</label
                >
                <p v-if="decisionError" class="decision-error" role="alert">{{ decisionError }}</p>
                <div class="buttons">
                  <div class="reject-control">
                    <button
                      class="btn reject-button"
                      :disabled="submitting || loadingList || !detail || loadingDetail"
                      :aria-expanded="rejectOpen"
                      @click="rejectOpen = !rejectOpen"
                    >
                      ✗ 驳回
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
                    {{ submitting ? '正在提交…' : '✓ 审核通过并更新' }}
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
        <footer>待审资料仅管理员可见 · 首次审核通过后附图公开</footer>
      </main>
    </div>
    <dialog ref="photoDialog" class="photo-viewer" aria-labelledby="admin-photo-title">
      <h2 id="admin-photo-title">{{ previewTitle }}</h2>
      <img v-if="previewSrc" :src="previewSrc" :alt="previewTitle" />
      <div class="buttons"><button class="btn" @click="photoDialog?.close()">关闭</button></div>
    </dialog>
  </div>
</template>

<style scoped src="./admin-review.css"></style>
