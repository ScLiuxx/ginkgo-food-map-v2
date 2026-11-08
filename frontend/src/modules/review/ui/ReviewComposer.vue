<script setup lang="ts">
import { computed, inject, nextTick, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useLoginStore } from '@/modules/auth/model/login.store'
import { authToken } from '@/shared/api/authSession'
import { fetchRestaurant, fetchRestaurants } from '@/modules/restaurant/api/restaurantApi'
import type { RestaurantReadModel } from '@/modules/restaurant/model/restaurant.types'
import { ratingDimensions } from '@/modules/restaurant/model/catalog.logic'
import HalfStarRating from './HalfStarRating.vue'
import {
  canteenWindows,
  diningDetails,
  compareFloors,
} from '@/modules/restaurant/model/campusDining'
import { validBody, reviewGatewayKey, unavailableReviewGateway } from '../model/review'
import { compressPhoto } from '../model/photos'
import { useReviewPhotos } from '../model/useReviewPhotos'
import { MAX_REVIEW_PHOTOS } from '../model/review'
import type { ReviewPublishReceipt } from '../model/review'
const props = defineProps<{
  restaurant?: RestaurantReadModel
  fixedTarget?: boolean
  targetHint?: string
  /** 详情对象可定制短操作文案，发布及认证流程共用。 */
  triggerLabel?: string
}>()
const emit = defineEmits<{ published: [] }>()
const auth = useLoginStore(),
  route = useRoute(),
  router = useRouter()
const gateway = inject(reviewGatewayKey, unavailableReviewGateway)
const dialog = ref<globalThis.HTMLDialogElement>(),
  opened = ref(false),
  loading = ref(false),
  busy = ref(false),
  processing = ref(false),
  error = ref('')
const restaurants = ref<RestaurantReadModel[]>([]),
  selected = ref(''),
  body = ref(''),
  recommended = ref(true)
const scores = ref({ taste: 7, value: 7, service: 7, ambience: 7 })
let requestId = '',
  submittedContent = '',
  owner: string | null = null,
  generation = 0,
  controller: globalThis.AbortController | undefined
const receipt = ref<ReviewPublishReceipt | null>(null)
const photoInput = ref<globalThis.HTMLInputElement>()
const photoDraft = useReviewPhotos(gateway, () => owner)
const { photos, pending: uploadingPhotos, failed: failedPhotos } = photoDraft
const locked = computed(() => opened.value && owner !== authToken.value)
const supportsPhotos = gateway.supportsPhotos !== false
// 五星仅用于输入展示；提交保留旧接口的四维十分制，不改变后端计分。
const total = computed(() => Object.values(scores.value).reduce((sum, score) => sum + score, 0) / 8)
const floor = ref('')
const windowId = ref('')
const current = computed(() => restaurants.value.find((r) => r.id === selected.value))
const dining = computed(() => (current.value ? diningDetails(current.value) : null))
const isCanteen = computed(
  () =>
    dining.value?.kind !== 'window' &&
    (current.value?.restaurantType === 'CANTEEN' || dining.value?.kind === 'canteen'),
)
const targetLocked = computed(
  () => !!props.fixedTarget || props.restaurant?.dining?.kind === 'window',
)
const windows = computed(() =>
  current.value
    ? canteenWindows(current.value.id, dining.value?.windows ?? null).filter((w) => !w.closed)
    : [],
)
// 缺失楼层也可访问，但不虚构楼层；窗口ID必须来自当前食堂的正式关联。
const floors = computed(() =>
  [...new Set(windows.value.map((w) => w.floor || '楼层待补充'))].sort(compareFloors),
)
const floorWindows = computed(() =>
  windows.value.filter((w) => (w.floor || '楼层待补充') === floor.value),
)
const chosenWindow = computed(() => floorWindows.value.find((w) => w.id === windowId.value))
const publishTarget = computed(() => (isCanteen.value ? chosenWindow.value?.id : current.value?.id))
const targetName = computed(() =>
  isCanteen.value ? chosenWindow.value?.name : current.value?.name,
)
const lockedName = computed(() =>
  [dining.value?.parent?.name, dining.value?.floor, current.value?.name]
    .filter(Boolean)
    .join(' · '),
)
// 同步清空级联，防止切换食堂或楼层后将旧窗口作为新对象提交。
watch(
  selected,
  () => {
    floor.value = ''
    windowId.value = ''
  },
  { flush: 'sync' },
)
/** 全站选食堂后加载真实完整窗口；切换对象后拒绝旧响应。 */
watch(selected, async (id) => {
  const item = restaurants.value.find((row) => row.id === id)
  if (
    !opened.value ||
    !item ||
    item.restaurantType !== 'CANTEEN' ||
    (item.dining?.windows !== undefined && item.dining.windows !== null)
  )
    return
  const ticket = generation
  try {
    const fresh = await fetchRestaurant(id, controller?.signal)
    if (ticket === generation && selected.value === id)
      restaurants.value = restaurants.value.map((row) => (row.id === id ? fresh : row))
  } catch {
    if (ticket === generation && selected.value === id)
      error.value = '窗口加载失败，请重新选择食堂重试。'
  }
})
watch(
  floor,
  () => {
    windowId.value = ''
  },
  { flush: 'sync' },
)
/** 原生dialog提供焦点圈定和Escape；关闭后释放草稿图片URL，旧异步请求不得回填。 */
function close() {
  const published = opened.value && !!receipt.value
  generation++
  controller?.abort()
  opened.value = false
  busy.value = false
  processing.value = false
  dialog.value?.close?.()
  photoDraft.clear(!!receipt.value)
  if (published) emit('published')
}
function requestClose() {
  if (!busy.value) close()
}
function finish() {
  close()
}
async function open() {
  if (opened.value) return
  if (!auth.authenticated) {
    await router.push({
      path: '/login',
      query: {
        redirect: props.restaurant
          ? `/${props.restaurant.dining?.kind === 'window' ? 'canteen-windows' : 'restaurants'}/${props.restaurant.id}?writeReview=1`
          : '/?writeReview=1',
      },
    })
    return
  }
  owner = authToken.value
  requestId = globalThis.crypto.randomUUID()
  submittedContent = ''
  body.value = ''
  scores.value = { taste: 7, value: 7, service: 7, ambience: 7 }
  recommended.value = true
  error.value = ''
  receipt.value = null
  opened.value = true
  await nextTick()
  if (dialog.value?.showModal) dialog.value.showModal()
  else if (dialog.value) dialog.value.open = true
  loading.value = true
  const ticket = ++generation
  controller = new globalThis.AbortController()
  restaurants.value = props.restaurant ? [props.restaurant] : []
  floor.value = ''
  windowId.value = ''
  selected.value = props.restaurant?.id ?? ''
  // 详情点评固定到当前对象，尤其不能从窗口悄悄切换到父食堂；全站选店入口仍读取完整目录。
  if (targetLocked.value && props.restaurant) {
    loading.value = false
    return
  }
  try {
    const all: RestaurantReadModel[] = []
    for (let page = 1; ; page++) {
      const result = await fetchRestaurants(
        {
          keyword: '',
          category: '',
          sort: 'score',
          hideClosed: false,
          maxPrice: null,
          maxDistanceMeters: null,
        },
        page,
        100,
        controller.signal,
      )
      all.push(...result.records)
      if (page >= result.totalPages) break
    }
    if (ticket !== generation) return
    restaurants.value = all.filter((r) => r.dining?.kind !== 'window')
    selected.value = props.restaurant?.id ?? restaurants.value[0]?.id ?? ''
  } catch {
    if (ticket === generation) error.value = '餐厅列表加载失败，请关闭后重试。'
  } finally {
    if (ticket === generation) loading.value = false
  }
}
/** 图片按顺序处理；过量明确提示，不静默丢图。账号切换或关窗后丢弃解码结果。 */
async function addPhotos(files: globalThis.FileList | null) {
  if (!files || processing.value || busy.value || locked.value) return
  if (!supportsPhotos) {
    error.value = '图片上传尚未开放，请先发布文字点评。'
    return
  }
  error.value = ''
  if (photos.value.length + files.length > MAX_REVIEW_PHOTOS) {
    error.value = '最多添加5张照片，请先移除多余照片。'
    return
  }
  processing.value = true
  const ticket = generation
  try {
    for (const file of Array.from(files)) {
      const compressed = await compressPhoto(file)
      if (ticket !== generation) return
      photoDraft.add(compressed)
    }
  } catch (reason) {
    if (ticket === generation)
      error.value = reason instanceof Error ? reason.message : '图片处理失败，请重试。'
  } finally {
    if (ticket === generation) processing.value = false
  }
}
function removePhoto(index: number) {
  if (!busy.value) photoDraft.remove(index)
}
async function submit() {
  if (busy.value || processing.value || receipt.value) return
  if (uploadingPhotos.value || failedPhotos.value) {
    error.value = '请等待照片上传完成；失败照片可重试或移除。'
    return
  }
  if (owner !== authToken.value || !auth.authenticated) {
    error.value = '登录身份已变化，请关闭后重新打开。'
    return
  }
  // 错误显示在固定操作区，避免校验提示被表单滚动区域遮住。
  if (!selected.value) {
    error.value = '请选择要评价的餐厅。'
    return
  }
  if (targetLocked.value && selected.value !== props.restaurant?.id) {
    error.value = '评价对象已变化，请关闭后重新打开。'
    return
  }
  if (!publishTarget.value) {
    error.value = isCanteen.value ? '请选择具体窗口后再评价。' : '请选择要评价的餐厅。'
    return
  }
  if (!validBody(body.value, 5, 500)) {
    error.value = '请填写5–500字的评价（不计首尾空白）。'
    return
  }
  busy.value = true
  error.value = ''
  const ticket = generation
  // 相同内容重试复用请求号；改动正文、评分或图片后是新请求，避免后端幂等内容冲突。
  const content = JSON.stringify([
    isCanteen.value || dining.value?.kind === 'window' ? 'WINDOW' : 'RESTAURANT',
    publishTarget.value,
    scores.value,
    recommended.value,
    body.value.trim(),
    photos.value.map((p) => p.fileId ?? p.url),
  ])
  if (submittedContent && submittedContent !== content) requestId = globalThis.crypto.randomUUID()
  submittedContent = content
  try {
    const result = await gateway.publish({
      restaurantId: publishTarget.value,
      ...(isCanteen.value || dining.value?.kind === 'window'
        ? { windowId: publishTarget.value }
        : {}),
      scores: { ...scores.value },
      recommended: recommended.value,
      body: body.value.trim(),
      photos: photos.value.map((p) => p.file),
      photoIds: photos.value.flatMap((p) => (p.fileId ? [p.fileId] : [])),
      clientRequestId: requestId,
    })
    if (ticket === generation && owner === authToken.value) {
      if (result) receipt.value = result
      else {
        close()
        emit('published')
      }
    }
  } catch (reason) {
    if (ticket === generation)
      error.value = reason instanceof Error ? reason.message : '发布失败，草稿已保留。'
  } finally {
    if (ticket === generation) busy.value = false
  }
}
watch(
  authToken,
  (token) => {
    if (!opened.value) return
    if (!token) {
      generation++
      controller?.abort()
      busy.value = false
      error.value = '登录状态已失效，当前正文已锁定保留，请复制后重新登录。不会以其他账号提交。'
    } else {
      close()
      body.value = ''
      receipt.value = null
    }
  },
  { flush: 'sync' },
)
watch(
  () => [route.query.writeReview, auth.authenticated],
  () => {
    if (route.query.writeReview === '1' && auth.authenticated) {
      void open()
      // 回跳意图只消费一次，避免发布刷新详情后再次弹出空表单。
      const query = { ...route.query }
      delete query.writeReview
      void router.replace({ path: route.path, query, hash: route.hash })
    }
  },
  { immediate: true },
)
onUnmounted(close)
</script>
<template>
  <button type="button" class="review-open" @click="open">
    {{ triggerLabel || '写评价' }}
  </button>
  <dialog ref="dialog" class="review-dialog" @cancel.prevent="requestClose">
    <section v-if="receipt" class="publish-receipt" role="status">
      <h2>{{ receipt.replayed ? '已确认此前发布成功' : '点评发布成功' }}</h2>
      <p>你的点评已发布成功，所选照片已按顺序绑定。</p>
      <dl>
        <dt>餐厅</dt>
        <dd>{{ targetName }}</dd>
        <dt>点评编号</dt>
        <dd class="receipt-id">{{ receipt.reviewId }}</dd>
      </dl>
      <p class="hint">关闭后可在对应店铺查看点评。</p>
      <button type="button" class="publish" @click="finish">完成</button>
    </section>
    <form v-else @submit.prevent="submit">
      <header>
        <h2>写一条评价</h2>
        <button type="button" aria-label="关闭评价" :disabled="busy" @click="requestClose">
          ×
        </button>
      </header>
      <div v-show="!locked" class="review-fields">
        <fieldset class="review-inputs" :disabled="busy || locked">
          <label class="target-label"
            >评价对象
            <select
              v-show="!targetLocked"
              v-model="selected"
              aria-label="评价对象"
              :disabled="loading || targetLocked"
            >
              <option value="">
                {{ loading ? '正在加载餐厅…' : '请选择餐厅' }}
              </option>
              <option v-for="r in restaurants" :key="r.id" :value="r.id">
                {{ r.name }}{{ r.closed ? '（已停业）' : '' }}
              </option>
            </select>
          </label>
          <div v-if="targetLocked" class="locked-target">
            <span aria-hidden="true">🔒</span><strong>{{ lockedName }}</strong
            ><small>已锁定</small>
          </div>
          <div v-if="isCanteen" class="window-selectors">
            <label
              >楼层<select v-model="floor" aria-label="楼层" :disabled="!windows.length">
                <option value="">请选择楼层</option>
                <option v-for="f in floors" :key="f" :value="f">{{ f }}</option>
              </select></label
            >
            <label
              >窗口<select v-model="windowId" aria-label="窗口" :disabled="!floor">
                <option value="">请选择具体窗口（必选）</option>
                <option v-for="w in floorWindows" :key="w.id" :value="w.id">
                  {{ w.name }}
                </option>
              </select></label
            >
          </div>
          <p v-if="isCanteen && !windows.length" class="target-empty">
            该食堂暂无收录窗口，请先添加窗口后再评价。
          </p>
          <p v-else-if="isCanteen" class="hint target-note">评价将发布到所选窗口</p>
          <p v-if="targetHint && !isCanteen" class="hint target-note">
            {{ targetHint }}
          </p>
          <RouterLink
            v-if="!targetLocked"
            class="add-missing"
            to="/restaurants/new"
            :aria-disabled="busy || locked"
            @click="busy || locked ? $event.preventDefault() : close()"
            >找不到要评价的店？添加餐厅 →</RouterLink
          >
          <section class="ratings-section" aria-label="四维评分">
            <div class="section-heading">
              <h3>四维评分 <small>支持半星</small></h3>
              <span
                >本条均分 <b>{{ total.toFixed(2) }}</b></span
              >
            </div>
            <div v-for="d in ratingDimensions" :key="d.key" class="slider-row">
              <label :for="'review-' + d.key">{{ d.key === 'taste' ? '味道' : d.label }}</label>
              <HalfStarRating :id="'review-' + d.key" v-model="scores[d.key]" :label="d.label" />
              <output>{{ (scores[d.key] / 2).toFixed(1) }}</output>
            </div>
          </section>
          <fieldset class="attitude">
            <legend>你的态度</legend>
            <div>
              <button type="button" :aria-pressed="recommended" @click="recommended = true">
                推荐
              </button>
              <button type="button" :aria-pressed="!recommended" @click="recommended = false">
                不推荐
              </button>
            </div>
          </fieldset>
          <div class="section-heading body-heading">
            <label for="review-body">写点什么</label
            ><small>发布账号：{{ auth.user?.username }}</small>
          </div>
          <div class="body-editor">
            <textarea
              id="review-body"
              v-model="body"
              rows="4"
              placeholder="比如：酱汁很香，分量足，下课高峰排队约5分钟…"
            /><span class="body-count">{{ Array.from(body.trim()).length }}/500 · 至少5字</span>
          </div>
          <!-- 初始上传框与缩略图列表共用一个选图入口，图片不再堆在大框下方。 -->
          <section
            class="photo-picker"
            aria-label="点评照片"
            @dragover.prevent
            @drop.prevent="addPhotos($event.dataTransfer?.files ?? null)"
          >
            <input
              ref="photoInput"
              class="photo-input"
              type="file"
              accept="image/jpeg,image/png,image/webp"
              multiple
              :disabled="processing || !supportsPhotos"
              @change="
                addPhotos(($event.target as HTMLInputElement).files)
                ;($event.target as HTMLInputElement).value = ''
              "
            />
            <button
              v-if="!photos.length"
              type="button"
              class="dropzone"
              :disabled="processing || !supportsPhotos"
              @click="photoInput?.click()"
            >
              照片 <span>最多5张，拖拽或点击选择</span>
            </button>
            <div v-else class="thumbs">
              <div v-for="(photo, i) in photos" :key="photo.url" class="photo-card">
                <div class="photo-preview">
                  <img :src="photo.url" alt="待发布照片" />
                  <button
                    type="button"
                    class="photo-remove"
                    aria-label="移除照片"
                    @click="removePhoto(i)"
                  >
                    ×
                  </button>
                </div>
                <p role="status" class="photo-status">
                  {{
                    photo.state === 'uploading'
                      ? '上传中…'
                      : photo.state === 'uploaded'
                        ? '已上传'
                        : photo.error
                  }}
                </p>
                <button
                  v-if="photo.state === 'failed'"
                  class="photo-retry"
                  type="button"
                  @click="photoDraft.retry(photo)"
                >
                  重试上传
                </button>
              </div>
              <button
                v-if="photos.length < MAX_REVIEW_PHOTOS"
                type="button"
                class="photo-add"
                aria-label="继续添加照片"
                :disabled="processing || !supportsPhotos"
                @click="photoInput?.click()"
              >
                <span aria-hidden="true">＋</span>
              </button>
            </div>
            <p v-if="!supportsPhotos || processing" class="hint">
              {{
                !supportsPhotos
                  ? '图片上传尚未开放，本次仅支持文字点评。'
                  : processing
                    ? '正在压缩图片…'
                    : ''
              }}
            </p>
          </section>
        </fieldset>
      </div>
      <section v-if="locked" class="locked-message">
        <label
          >待复制的正文<textarea
            :value="body"
            readonly
            rows="4"
            @focus="($event.target as HTMLTextAreaElement).select()"
          />
        </label>
      </section>
      <footer>
        <!-- 反馈与提交按钮放在同一个固定区域，校验、网络错误和等待状态始终可见。 -->
        <p v-if="error" role="alert" class="error submit-feedback">
          {{ error }}
        </p>
        <p v-else-if="busy || loading || processing" role="status" class="hint submit-feedback">
          {{
            busy
              ? '正在发布点评，请稍候…'
              : loading
                ? '正在加载餐厅，请稍候…'
                : '正在处理照片，请稍候…'
          }}
        </p>
        <button type="button" class="cancel-publish" :disabled="busy" @click="requestClose">
          取消
        </button>
        <button
          class="publish"
          :disabled="
            busy ||
            processing ||
            loading ||
            locked ||
            uploadingPhotos ||
            failedPhotos ||
            !publishTarget
          "
          type="submit"
        >
          {{ busy ? '发布中…' : uploadingPhotos ? '照片上传中…' : '发布评价' }}
        </button>
      </footer>
    </form>
  </dialog>
</template>
<style scoped src="./review-composer.css"></style>
