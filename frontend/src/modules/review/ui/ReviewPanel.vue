<script setup lang="ts">
import { inject, onUnmounted, provide, ref, watch, computed } from 'vue'
import { activeReplyEditorKey } from '../model/replyEditor'
import { useRouter, useRoute } from 'vue-router'
import { authToken } from '@/shared/api/authSession'
import { useLoginStore } from '@/modules/auth/model/login.store'
import ReviewEntry from './ReviewEntry.vue'
import ReviewHistory from './ReviewHistory.vue'
import { reviewGatewayKey, unavailableReviewGateway, validBody } from '../model/review'
import type { ReviewItem, ReviewSort } from '../model/review'
import { detailPhotos, type DetailPhotoUpdate } from '../model/detailPhotos'
const props = defineProps<{
    restaurantId: string
    preview?: boolean
    revision?: number
    starScale?: boolean
    title?: string
  }>(),
  emit = defineEmits<{ changed: []; photos: [update: DetailPhotoUpdate] }>()
const gateway = inject(reviewGatewayKey, unavailableReviewGateway),
  auth = useLoginStore(),
  router = useRouter(),
  route = useRoute()
const records = ref<ReviewItem[]>([]),
  loading = ref(false),
  error = ref(''),
  texts = ref<Record<string, string>>({}),
  busy = ref(false),
  photo = ref(''),
  lightbox = ref<globalThis.HTMLDialogElement>(),
  deleting = ref<ReviewItem | null>(null),
  confirm = ref<globalThis.HTMLDialogElement>()
let controller: globalThis.AbortController | undefined,
  generation = 0
const replyRequests = new Map<string, { body: string; id: string }>()
provide(activeReplyEditorKey, ref<string | null>(null))
const page = ref(1),
  totalPages = ref(0),
  totalGroups = ref(0),
  totalReviews = ref(0)
let requestedPage = 1
const currentOnly = ref(false)
const sort = ref<ReviewSort>('DEFAULT')
// 切换排序回到第一页；load取消旧请求并核验代次，阻止旧排序响应覆盖新选择。
watch(sort, () => {
  cancelDelete()
  void load(1)
})
const contentFilter = ref<'all' | 'recommended' | 'photos'>('all')
watch(contentFilter, () => {
  void load(1)
})
const viewerId = computed(() =>
  auth.authenticated && auth.user ? String(auth.user.id) : undefined,
)
const deleteError = ref('')
// 仅按明确的计分状态筛选；未知状态不自行猜测成历史或当前评分。
const visibleRecords = computed(() =>
  records.value.filter((item) => !currentOnly.value || item.scoringStatus === 'current'),
)
const hasScoringStatus = computed(() =>
  records.value.some((item) => item.scoringStatus !== undefined),
)
const wall = computed(() =>
  [...new Map(records.value.flatMap((r) => r.photos).map((p) => [p.id, p])).values()].slice(0, 12),
)
/** 真实传输由适配器提供；未接通时显示失败状态，绝不使用模拟评价回填。 */
async function load(targetPage = page.value) {
  const ticket = ++generation
  const restaurantId = props.restaurantId
  controller?.abort()
  controller = new globalThis.AbortController()
  loading.value = true
  records.value = []
  error.value = ''
  // 复用当前页响应供详情顶部展示，避免为图库额外遍历点评或混入其他对象的图片。
  emit('photos', { restaurantId, state: 'loading', photos: [] })
  requestedPage = targetPage
  try {
    // 新条件尚无服务端参数：完整读取作者组再筛选分页；失败整体报错，不把当前页冒充全部。
    // 沿用取消信号和代次校验，切换店铺或条件后不接受晚到的旧响应。
    const signal = controller.signal
    const result = {
      ...(await gateway.list(
        restaurantId,
        signal,
        props.starScale && contentFilter.value !== 'all' ? 1 : targetPage,
        sort.value,
      )),
    }
    if (ticket !== generation) return
    const filter = contentFilter.value
    if (props.starScale && filter !== 'all') {
      const all = [...result.records]
      for (let next = 2; next <= result.totalPages; next++) {
        const batch = await gateway.list(restaurantId, signal, next, sort.value)
        if (ticket !== generation) return
        all.push(...batch.records)
      }
      const matching = [...new Map(all.map((item) => [item.id, item])).values()].filter((item) =>
        filter === 'recommended' ? item.recommended : item.photos.length > 0,
      )
      const pageSize = result.pageSize || 8
      result.totalGroups = matching.length
      result.totalPages = Math.ceil(matching.length / pageSize)
      result.page = Math.max(1, Math.min(targetPage, result.totalPages || 1))
      result.records = matching.slice((result.page - 1) * pageSize, result.page * pageSize)
    }
    if (ticket === generation) {
      records.value = result.records
      page.value = result.page
      totalPages.value = result.totalPages
      totalGroups.value = result.totalGroups
      totalReviews.value = result.totalReviews
      emit('photos', { restaurantId, state: 'ready', photos: detailPhotos(result.records) })
    }
  } catch (reason) {
    if (ticket === generation) {
      error.value = reason instanceof Error ? reason.message : '评价加载失败，请重试。'
      emit('photos', { restaurantId, state: 'error', photos: [] })
    }
  } finally {
    if (ticket === generation) loading.value = false
  }
}
watch(
  () => [props.restaurantId, props.revision],
  () => {
    cancelDelete()
    page.value = 1
    totalPages.value = totalGroups.value = totalReviews.value = 0
    void load(1)
  },
  { immediate: true },
)
watch(authToken, () => {
  texts.value = {}
  replyRequests.clear()
  deleting.value = null
  confirm.value?.close?.()
  // 身份改变后重新读取可管理标记，避免沿用上一账号看到的删除入口。
  void load()
})
onUnmounted(() => {
  generation++
  controller?.abort()
})
async function reply(item: ReviewItem) {
  if (!auth.authenticated) {
    await router.push({ path: '/login', query: { redirect: route.path } })
    return
  }
  const text = texts.value[item.id] ?? ''
  if (!validBody(text, 1, 200)) {
    error.value = '回复需要1–200字。'
    return
  }
  if (busy.value) return
  busy.value = true
  const owner = authToken.value,
    ticket = generation
  // 网络失败保留同次请求号；账号切换或正文改变才生成新号。
  let request = replyRequests.get(item.id)
  if (!request || request.body !== text.trim()) {
    request = { body: text.trim(), id: globalThis.crypto.randomUUID() }
    replyRequests.set(item.id, request)
  }
  try {
    await gateway.reply(item.id, text.trim(), request.id)
    if (owner === authToken.value && ticket === generation) {
      replyRequests.delete(item.id)
      texts.value[item.id] = ''
      await load()
      emit('changed')
    }
  } catch (reason) {
    if (ticket === generation && owner === authToken.value)
      error.value = reason instanceof Error ? reason.message : '回复失败，文字已保留。'
  } finally {
    busy.value = false
  }
}
function askDelete(item: ReviewItem) {
  if (!props.preview && (!viewerId.value || item.authorId !== viewerId.value)) return
  deleteError.value = ''
  deleting.value = item
  confirm.value?.showModal()
}
function cancelDelete() {
  confirm.value?.close?.()
  deleting.value = null
  deleteError.value = ''
}
async function remove() {
  if (!deleting.value || busy.value) return
  if (!props.preview && (!viewerId.value || deleting.value.authorId !== viewerId.value)) return
  busy.value = true
  deleteError.value = ''
  const ticket = generation,
    owner = authToken.value
  try {
    await gateway.remove(deleting.value.id)
    if (ticket === generation && owner === authToken.value) {
      confirm.value?.close?.()
      deleting.value = null
      // 归组及代表可能移动，回第一页避免删掉末页最后一组后停在空页。
      await load(1)
      emit('changed')
    }
  } catch (reason) {
    if (ticket === generation && owner === authToken.value)
      deleteError.value = reason instanceof Error ? reason.message : '删除失败，可重试。'
  } finally {
    busy.value = false
  }
}
function view(url: string) {
  photo.value = url
  lightbox.value?.showModal()
}
</script>
<template>
  <section
    class="review-panel"
    :class="{ 'star-panel': starScale }"
    aria-labelledby="reviews-title"
  >
    <header>
      <h2 id="reviews-title">
        {{ title || '同学点评' }} <small v-if="starScale">({{ totalReviews }})</small>
      </h2>
      <div v-if="starScale" class="content-filters" aria-label="点评筛选">
        <button
          type="button"
          :aria-pressed="contentFilter === 'all'"
          @click="contentFilter = 'all'"
        >
          全部
        </button>
        <button
          type="button"
          :aria-pressed="contentFilter === 'recommended'"
          @click="contentFilter = 'recommended'"
        >
          推荐
        </button>
        <button
          type="button"
          :aria-pressed="contentFilter === 'photos'"
          @click="contentFilter = 'photos'"
        >
          有图
        </button>
      </div>
      <div v-if="!preview" class="content-filters" aria-label="点评排序">
        <button type="button" :aria-pressed="sort === 'DEFAULT'" @click="sort = 'DEFAULT'">
          默认排序
        </button>
        <button type="button" :aria-pressed="sort === 'TIME'" @click="sort = 'TIME'">
          时间排序
        </button>
      </div>
      <button type="button" :disabled="loading" @click="load(1)">刷新评价</button>
    </header>
    <p v-if="!preview" class="scoring-rule">
      {{ sort === 'DEFAULT' ? '点赞数优先，同赞时高等级作者优先。' : '按发布时间从新到旧。' }}
    </p>
    <p v-if="!preview && !loading && !error" class="scoring-rule">
      <template v-if="starScale">{{
        contentFilter === 'all' ? `${totalGroups} 位同学` : `${totalGroups} 条符合条件`
      }}</template>
      <template v-else
        >{{ totalGroups }}位同学 · {{ totalReviews }}条点评。每位同学展示最新点评。</template
      >
    </p>
    <template v-if="preview && hasScoringStatus">
      <p class="scoring-rule">
        同一账号可记录多次用餐，只有最新一条有效点评参与评分；历史点评仍可阅读。
      </p>
      <div class="review-tabs" aria-label="点评显示范围">
        <button type="button" :aria-pressed="!currentOnly" @click="currentOnly = false">
          全部用餐记录
        </button>
        <button type="button" :aria-pressed="currentOnly" @click="currentOnly = true">
          只看当前计分
        </button>
      </div>
    </template>
    <p v-if="loading" role="status">正在加载评价…</p>
    <div v-else-if="error" role="alert">
      <p>{{ error }}</p>
      <button type="button" @click="load(requestedPage)">重试</button>
    </div>
    <p v-else-if="!records.length" class="empty">
      {{ contentFilter === 'all' ? '还没有人评价，来当第一个吧。' : '暂无符合条件的点评' }}
    </p>
    <details v-if="preview && wall.length" class="student-album">
      <summary>同学上传的照片（{{ wall.length }}） · 查看照片与原点评</summary>
      <p>下方各条点评保留作者和照片对应关系，点击照片可查看大图。</p>
    </details>
    <div v-if="preview" class="photos">
      <button v-for="p in wall" :key="p.id" type="button" @click="view(p.url)">
        <img :src="p.url" alt="餐厅评价照片" loading="lazy" />
      </button>
    </div>
    <ReviewEntry
      v-for="item in visibleRecords"
      :key="item.id"
      :item="item"
      :preview="preview"
      :star-scale="starScale"
      :authenticated="auth.authenticated"
      :viewer-id="viewerId"
      :busy="busy"
      :reply-texts="texts"
      :show-history="!currentOnly"
      :replies-enabled="gateway.supportsReplies !== false"
      @delete="askDelete"
      @photo="view"
      @reply="reply"
      @text="(id, text) => (texts[id] = text)"
    >
      <template v-if="!preview && gateway.history && item.historyCount" #history>
        <ReviewHistory
          :restaurant-id="restaurantId"
          :item="item"
          :viewer-id="viewerId"
          :star-scale="starScale"
          @photo="view"
          @refresh="load()"
          @delete="askDelete"
        />
      </template>
    </ReviewEntry>
    <nav
      v-if="!loading && !error && totalPages > 1"
      class="review-pagination"
      aria-label="点评分页"
    >
      <button type="button" :disabled="page <= 1" @click="load(page - 1)">上一页点评</button>
      <span aria-live="polite">第{{ page }} / {{ totalPages }}页</span>
      <button type="button" :disabled="page >= totalPages" @click="load(page + 1)">
        下一页点评
      </button>
    </nav>
    <Teleport to="body">
      <dialog
        ref="confirm"
        aria-labelledby="delete-review-title"
        @cancel.prevent="cancelDelete"
        @close="deleting = null"
      >
        <h3 id="delete-review-title">确定删除这条评价？</h3>
        <p>回复和照片将一并删除，删除后无法恢复。</p>
        <p v-if="deleting?.scoringStatus === 'current'">
          删除后，你在这家店的上一条有效点评会重新参与评分；没有历史点评时移除你的评分。
        </p>
        <p v-if="preview">当前仅演示页面变化，不会删除真实数据，可通过“重置示例”恢复。</p>
        <p v-if="deleteError" role="alert">{{ deleteError }}</p>
        <button type="button" autofocus @click="cancelDelete">取消</button
        ><button type="button" :disabled="busy" @click="remove">确认删除</button>
      </dialog>
    </Teleport>
    <dialog ref="lightbox" class="lightbox">
      <button type="button" aria-label="关闭大图" @click="lightbox?.close()">关闭 ×</button
      ><img :src="photo || undefined" alt="评价照片大图" />
    </dialog>
  </section>
</template>
<style scoped>
/* 详情页使用自然内容流；原有独立预览继续沿用原展示口径。 */
.star-panel.review-panel {
  padding: 0;
  border: 0;
  border-radius: 0;
  background: white;
}
.star-panel > header {
  flex-wrap: wrap;
  gap: 10px 18px;
}
.star-panel h2 {
  font-size: 20px;
  margin-right: auto;
}
.star-panel h2 small {
  font-size: 14px;
  font-weight: 400;
  color: #98a0a9;
}
.star-panel > header > button {
  border: 0;
  padding: 4px;
  color: #9aa2ab;
  font-size: 12px;
}
.star-panel .scoring-rule {
  margin: 8px 0;
  padding: 0;
  background: none;
  color: #99a1aa;
  font-size: 12px;
}
.content-filters {
  display: flex;
  gap: 4px;
}
.content-filters button {
  border: 0;
  border-radius: 5px;
  padding: 4px 12px;
  color: #8b95a0;
  font-size: 13px;
}
.content-filters button[aria-pressed='true'] {
  background: #fff4e9;
  color: #ed8424;
}
.star-panel .empty {
  padding: 40px 0;
  color: #9aa2ab;
  font-size: 13px;
}
.star-panel .review-pagination button {
  border-color: #edf0f2;
  color: #7c8895;
}
@media (max-width: 480px) {
  .star-panel h2 {
    flex: 1 0 100%;
    font-size: 18px;
  }
  .star-panel > header > button {
    margin-left: auto;
  }
}
.scoring-rule {
  font-size: 12px;
  line-height: 1.8;
  color: #82745f;
  background: #fff8e9;
  padding: 12px;
  border-radius: 10px;
}
.review-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 16px 0;
}
.review-tabs button {
  border-radius: 20px;
  font-size: 12px;
  min-height: 40px;
}
.review-tabs button[aria-pressed='true'] {
  background: #fff2cc;
  border-color: #e9b949;
  color: #7a5515;
}
.review-panel {
  font-family:
    -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  padding: 26px;
  background: #fffdf8;
  border: 1px solid #e2d9c9;
  border-radius: 18px;
  min-width: 0;
}
header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  justify-content: space-between;
}
h2 {
  font-size: 17px;
  margin: 0;
}
button {
  font: inherit;
  background: transparent;
  border: 1px solid #d9cfbb;
  border-radius: 7px;
  padding: 8px 12px;
  color: #74603d;
  cursor: pointer;
}
.review-panel > div[role='alert'] {
  font-size: 13px;
  color: #8d5a38;
  padding: 20px 0;
}
.empty {
  padding: 30px;
  text-align: center;
  color: #80735d;
}
.photos {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin: 14px 0;
}
.photos:empty {
  display: none;
}
.photos button {
  padding: 0;
  border: 0;
}
.photos img {
  width: 82px;
  height: 82px;
  object-fit: cover;
  border-radius: 8px;
}
dialog {
  max-width: calc(100% - 28px);
  border: 0;
  border-radius: 12px;
  padding: 20px;
  background: #fffdf8;
}
.lightbox img {
  display: block;
  max-width: 85vw;
  max-height: 75dvh;
  margin-top: 10px;
}
dialog::backdrop {
  background: #242018bb;
}
@media (max-width: 400px) {
  .review-panel {
    padding: 16px;
  }
  .verdict {
    font-size: 11px;
  }
  .replies {
    padding: 8px;
  }
}

.review-evidence,
.student-album {
  font-size: 12px;
  color: #61758a;
  background: #f1f5f8;
  padding: 10px 12px;
  border-radius: 9px;
  margin: 12px 0;
  line-height: 1.8;
}
.review-evidence summary,
.student-album summary {
  cursor: pointer;
}
.review-evidence p {
  margin: 8px 0 0;
}
.review-panel {
  background: white;
}
.review-pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 20px;
  font-size: 13px;
}
button {
  min-height: 44px;
}
button:disabled {
  opacity: 0.5;
  cursor: default;
}
</style>
