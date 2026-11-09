<script setup lang="ts">
import { computed, inject, onBeforeUnmount, provide, ref, watch } from 'vue'
import { useLoginStore } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { fetchMyReviews, type MyReviewGroup } from '../api/myReviewApi'
import { reviewGatewayKey, unavailableReviewGateway, type ReviewItem } from '../model/review'
import { activeReplyEditorKey } from '../model/replyEditor'
import ReviewEntry from './ReviewEntry.vue'
import ReviewHistory from './ReviewHistory.vue'

/** 本人跨店列表；归组、计数及可见性均由服务端完成，历史和删除复用现有点评能力。 */
const props = defineProps<{ publicUserId?: string; profileLayout?: boolean }>()
const emit = defineEmits<{ total: [value: number | null] }>()
const auth = useLoginStore()
const gateway = inject(reviewGatewayKey, unavailableReviewGateway)
provide(activeReplyEditorKey, ref<string | null>(null))
const records = ref<MyReviewGroup[]>([])
const page = ref(1),
  totalPages = ref(0),
  totalGroups = ref(0),
  totalReviews = ref(0)
const loading = ref(false),
  loaded = ref(false),
  busy = ref(false)
const error = ref(''),
  deleteError = ref(''),
  notice = ref('')
const deleting = ref<ReviewItem | null>(null)
const confirm = ref<globalThis.HTMLDialogElement>()
const lightbox = ref<globalThis.HTMLDialogElement>()
const photo = ref('')
const viewerId = computed(() =>
  auth.authenticated && auth.user ? String(auth.user.id) : undefined,
)
let controller: globalThis.AbortController | undefined
let generation = 0,
  context = 0,
  requestedPage = 1

/** 一个请求只展示当前页；晚到响应不能覆盖换号、翻页或卸载后的状态。 */
async function load(target = page.value) {
  const userId = viewerId.value,
    token = authToken.value
  if (!userId || !token) return
  const ticket = ++generation,
    owner = context
  controller?.abort()
  controller = new globalThis.AbortController()
  requestedPage = target
  records.value = []
  loaded.value = false
  emit('total', null)
  loading.value = true
  error.value = ''
  try {
    const result = props.publicUserId
      ? await fetchMyReviews(
          target,
          props.publicUserId,
          token,
          controller.signal,
          props.publicUserId,
        )
      : await fetchMyReviews(target, userId, token, controller.signal)
    if (ticket !== generation || owner !== context) return
    records.value = result.records
    page.value = result.page
    totalPages.value = result.totalPages
    totalGroups.value = result.totalGroups
    totalReviews.value = result.totalReviews
    emit('total', result.totalReviews)
    loaded.value = true
  } catch (reason) {
    if (ticket === generation && owner === context) error.value = toErrorMessage(reason)
  } finally {
    if (ticket === generation && owner === context) loading.value = false
  }
}
function cancelDelete() {
  if (busy.value) return
  confirm.value?.close()
  deleting.value = null
  deleteError.value = ''
}
function askDelete(item: ReviewItem) {
  if (busy.value || !viewerId.value || item.authorId !== viewerId.value) return
  deleteError.value = ''
  deleting.value = item
  confirm.value?.showModal()
}
/** 删除失败留在确认框；204后只刷新第一页，不因读取失败再次发送删除请求。 */
async function remove() {
  const item = deleting.value
  if (!item || busy.value || !viewerId.value || item.authorId !== viewerId.value) return
  const owner = context
  busy.value = true
  deleteError.value = ''
  try {
    await gateway.remove(item.id)
    if (owner !== context) return
    confirm.value?.close()
    deleting.value = null
    notice.value = '点评已删除。'
    await load(1)
  } catch (reason) {
    if (owner === context) deleteError.value = toErrorMessage(reason)
  } finally {
    if (owner === context) busy.value = false
  }
}
function viewPhoto(url: string) {
  photo.value = url
  lightbox.value?.showModal()
}
/** 切换会话立即清除私有正文及模态窗口，旧DELETE回执不得更改新账号的页面。 */
watch(
  () => [auth.authenticated, auth.user?.id, authToken.value, props.publicUserId],
  () => {
    context++
    generation++
    controller?.abort()
    confirm.value?.close()
    lightbox.value?.close()
    records.value = []
    deleting.value = null
    photo.value = error.value = deleteError.value = notice.value = ''
    loading.value = loaded.value = busy.value = false
    totalGroups.value = totalReviews.value = totalPages.value = 0
    page.value = 1
    if (viewerId.value) void load(1)
  },
  { immediate: true, flush: 'sync' },
)
onBeforeUnmount(() => {
  context++
  generation++
  controller?.abort()
})
// 主页的统一工具栏只代理当前列表刷新，读取与写入进行中均禁用重复操作。
defineExpose({ refresh: () => load(1), refreshing: computed(() => loading.value || busy.value) })
</script>

<template>
  <section
    class="my-reviews"
    :class="{ 'profile-review-list': profileLayout }"
    :aria-label="publicUserId ? '用户公开点评' : '本人点评列表'"
    :aria-busy="loading"
  >
    <header v-if="!profileLayout" class="my-reviews-toolbar">
      <p v-if="loaded">
        当前可查看 <strong>{{ totalGroups }}</strong> 家店 ·
        <strong>{{ totalReviews }}</strong> 条点评
      </p>
      <p v-else>每家店保留最新体验，历史点评随时回看。</p>
      <button type="button" :disabled="loading || busy" @click="load(1)">刷新</button>
      <RouterLink v-if="!publicUserId" to="/?writeReview=1">写点评 ›</RouterLink>
    </header>
    <p v-if="notice" role="status" class="my-review-notice">{{ notice }}</p>
    <p v-if="loading" class="my-review-status" role="status">
      {{ publicUserId ? '正在加载公开点评…' : '正在加载我的点评…' }}
    </p>
    <div v-else-if="error" class="my-review-error" role="alert">
      <p>{{ notice ? '删除已完成，但列表刷新失败：' : '' }}{{ error }}</p>
      <button type="button" :disabled="busy" @click="load(requestedPage)">重试加载</button>
    </div>
    <div v-else-if="loaded && !records.length" class="my-review-empty">
      <h2>{{ totalGroups ? '当前页内容已变化' : '还没有可展示的点评' }}</h2>
      <p>
        {{
          totalGroups
            ? '其他设备的操作可能改变了分页，回到第一页查看最新内容。'
            : publicUserId
              ? '这位同学暂时没有可展示的公开点评。'
              : '从一次用餐体验开始，记录你喜欢的校园好味道。'
        }}
      </p>
      <button v-if="totalGroups" type="button" @click="load(1)">返回第一页</button>
      <RouterLink v-else-if="!publicUserId" to="/?writeReview=1">写第一条点评 →</RouterLink>
    </div>
    <article v-for="group in records" :key="group.item.id" class="my-review-group">
      <header class="my-review-store">
        <RouterLink
          :to="`/${group.restaurant.targetType === 'WINDOW' ? 'canteen-windows' : 'restaurants'}/${group.restaurant.id}`"
          ><h2>{{ group.restaurant.name }}</h2>
          <span aria-hidden="true">›</span></RouterLink
        ><span v-if="group.restaurant.businessStatus === 'CLOSED'" class="closed-store"
          >暂停营业</span
        >
      </header>
      <ReviewEntry
        :item="group.item"
        star-scale
        :profile-entry="profileLayout"
        :authenticated="auth.authenticated"
        :viewer-id="viewerId"
        :busy="busy"
        :reply-texts="{}"
        :show-history="false"
        :replies-enabled="gateway.supportsReplies === true && !!gateway.listReplies"
        @delete="askDelete"
        @photo="viewPhoto"
      >
        <template v-if="group.item.historyCount" #history>
          <ReviewHistory
            :restaurant-id="group.restaurant.id"
            :window="group.restaurant.targetType === 'WINDOW'"
            :item="group.item"
            :viewer-id="viewerId"
            star-scale
            @delete="askDelete"
            @photo="viewPhoto"
            @refresh="load(1)"
          />
        </template>
      </ReviewEntry>
    </article>
    <nav v-if="loaded && totalPages > 1" class="my-review-pagination" aria-label="我的点评分页">
      <button type="button" :disabled="loading || busy || page <= 1" @click="load(page - 1)">
        上一页</button
      ><span aria-live="polite">第 {{ page }} / {{ totalPages }} 页</span
      ><button
        type="button"
        :disabled="loading || busy || page >= totalPages"
        @click="load(page + 1)"
      >
        下一页
      </button>
    </nav>
    <Teleport to="body">
      <dialog
        ref="confirm"
        class="my-review-confirm"
        aria-labelledby="my-delete-title"
        @cancel.prevent="cancelDelete"
      >
        <h2 id="my-delete-title">确定删除这条点评？</h2>
        <p v-if="deleteError" role="alert">{{ deleteError }}</p>
        <footer>
          <button type="button" :disabled="busy" autofocus @click="cancelDelete">取消</button
          ><button type="button" class="confirm-remove" :disabled="busy" @click="remove">
            {{ busy ? '正在删除…' : '确认删除' }}
          </button>
        </footer>
      </dialog>
      <dialog ref="lightbox" class="my-review-lightbox" aria-label="点评照片大图">
        <button type="button" aria-label="关闭大图" @click="lightbox?.close()">关闭 ×</button
        ><img :src="photo || undefined" alt="点评照片大图" />
      </dialog>
    </Teleport>
  </section>
</template>

<style scoped src="./my-reviews.css"></style>
