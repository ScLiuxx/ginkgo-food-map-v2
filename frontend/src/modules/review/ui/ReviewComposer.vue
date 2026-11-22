<script setup lang="ts">
import { createRequestId } from '@/shared/lib/createRequestId'
import { computed, inject, nextTick, onUnmounted, ref, useId, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { reviewDestination } from '@/shared/lib/reviewNavigation'
import { useLoginStore } from '@/modules/auth/model/login.store'
import { authToken } from '@/shared/api/authSession'
import type { RestaurantReadModel } from '@/modules/restaurant/model/restaurant.types'
import { ratingDimensions } from '@/modules/restaurant/model/catalog.logic'
import HalfStarRating from './HalfStarRating.vue'
import ReviewTargetSearch from './ReviewTargetSearch.vue'
import { validBody, reviewGatewayKey, unavailableReviewGateway } from '../model/review'
import { compressPhoto } from '../model/photos'
import { useReviewPhotos } from '../model/useReviewPhotos'
import { MAX_REVIEW_PHOTOS } from '../model/review'
import { recommendationLabels, reviewRecommendations } from '../model/review'
import type { ReviewPublishReceipt, ReviewRecommendation } from '../model/review'
const props = defineProps<{
  restaurant?: RestaurantReadModel
  fixedTarget?: boolean
  /** 详情对象可定制短操作文案，发布及认证流程共用。 */
  triggerLabel?: string
  /** 布局只承载弹窗，入口由全站统一链接提供。 */
  hideTrigger?: boolean
}>()
const emit = defineEmits<{ published: [] }>()
const auth = useLoginStore(),
  route = useRoute(),
  router = useRouter()
const gateway = inject(reviewGatewayKey, unavailableReviewGateway)
const titleId = 'review-composer-title-' + useId()
const dialog = ref<globalThis.HTMLDialogElement>(),
  opened = ref(false),
  loading = ref(false),
  busy = ref(false),
  processing = ref(false),
  error = ref('')
const restaurants = ref<RestaurantReadModel[]>([]),
  selected = ref(''),
  body = ref(''),
  recommendation = ref<ReviewRecommendation | null>(null)
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
const current = computed(() => restaurants.value.find((r) => r.id === selected.value))
const targetLocked = computed(() => !!props.fixedTarget)
const publishTarget = computed(() => current.value?.id)
const targetName = computed(() => current.value?.name)
let previousOverflow: string | undefined
let returnFocus: globalThis.HTMLElement | undefined
/** 全屏编辑锁住背景，关闭和卸载时恢复；草稿仍由原有关闭流程清理。 */
function unlockPage() {
  if (previousOverflow === undefined) return
  globalThis.document.documentElement.style.overflow = previousOverflow
  previousOverflow = undefined
}
/** 部分手机键盘只缩小可视视口；发布区跟随可见高度，缩放时交给浏览器处理。 */
function fitViewport() {
  const viewport = globalThis.visualViewport
  if (opened.value && viewport && globalThis.innerWidth <= 700 && viewport.scale === 1) {
    dialog.value?.style.setProperty('height', `${viewport.height}px`)
    dialog.value?.style.setProperty('top', `${viewport.offsetTop}px`)
  } else {
    dialog.value?.style.removeProperty('height')
    dialog.value?.style.removeProperty('top')
  }
}
function stopViewport() {
  globalThis.visualViewport?.removeEventListener('resize', fitViewport)
  globalThis.visualViewport?.removeEventListener('scroll', fitViewport)
  globalThis.removeEventListener('resize', fitViewport)
}
watch(
  opened,
  (value) => {
    if (value) {
      globalThis.visualViewport?.addEventListener('resize', fitViewport)
      globalThis.visualViewport?.addEventListener('scroll', fitViewport)
      globalThis.addEventListener('resize', fitViewport)
    } else stopViewport()
    fitViewport()
  },
  { flush: 'post' },
)
/** 原生dialog提供焦点圈定和Escape；关闭后释放草稿图片URL，旧异步请求不得回填。 */
function close() {
  const wasOpened = opened.value
  const published = opened.value && !!receipt.value
  generation++
  controller?.abort()
  opened.value = false
  busy.value = false
  processing.value = false
  dialog.value?.close?.()
  stopViewport()
  unlockPage()
  if (wasOpened && returnFocus?.isConnected) returnFocus.focus({ preventScroll: true })
  returnFocus = undefined
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
        redirect: router.resolve(reviewDestination(route)).fullPath,
      },
    })
    return
  }
  owner = authToken.value
  returnFocus =
    globalThis.document.activeElement instanceof globalThis.HTMLElement
      ? globalThis.document.activeElement
      : undefined
  previousOverflow = globalThis.document.documentElement.style.overflow
  globalThis.document.documentElement.style.overflow = 'hidden'
  requestId = createRequestId()
  submittedContent = ''
  body.value = ''
  scores.value = { taste: 7, value: 7, service: 7, ambience: 7 }
  recommendation.value = null
  error.value = ''
  receipt.value = null
  opened.value = true
  const ticket = ++generation
  await nextTick()
  // 布局跨页存活，打开与实际显示之间若已返回或换号，不再显示已作废的草稿。
  if (ticket !== generation || !opened.value) return
  if (dialog.value?.showModal) dialog.value.showModal()
  else if (dialog.value) dialog.value.open = true
  loading.value = true
  controller = new globalThis.AbortController()
  restaurants.value = props.restaurant ? [props.restaurant] : []
  selected.value = props.restaurant?.id ?? ''
  // 全站选店由搜索组件按需分页读取，详情对象继续保持固定目标。
  loading.value = false
}
function chooseRestaurant(item: RestaurantReadModel) {
  restaurants.value = [item]
  selected.value = item.id
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
    error.value = '请选择要评价的餐厅。'
    return
  }
  if (recommendation.value === null) {
    error.value = '请选择推荐、一般或不推荐。'
    return
  }
  if (!validBody(body.value, 1, 500)) {
    error.value = '请填写评价，最多500字（不计首尾空白）。'
    return
  }
  busy.value = true
  error.value = ''
  const ticket = generation
  // 相同内容重试复用请求号；改动正文、评分或图片后是新请求，避免后端幂等内容冲突。
  const content = JSON.stringify([
    publishTarget.value,
    scores.value,
    recommendation.value,
    body.value.trim(),
    photos.value.map((p) => p.fileId ?? p.url),
  ])
  if (submittedContent && submittedContent !== content) requestId = createRequestId()
  submittedContent = content
  try {
    const result = await gateway.publish({
      restaurantId: publishTarget.value,
      scores: { ...scores.value },
      recommendation: recommendation.value,
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
// 布局中的编辑器跨页面存活；真实地址变化（含同路径的历史查询）只清理资源。
// 打开意图的加入／消费不是离开来源页，比较时必须排除它，避免打开后立即关窗。
watch(
  () => {
    const query = { ...route.query }
    delete query.writeReview
    return router.resolve({ path: route.path, query, hash: route.hash }).fullPath
  },
  close,
  { flush: 'sync' },
)
watch(
  () => [route.query.writeReview, auth.authenticated],
  () => {
    if (route.query.writeReview === '1') {
      // 未登录时先去认证，回跳仍指向原阅读页；不能同时消费意图造成导航竞争。
      if (!auth.authenticated) {
        void router.replace({
          path: '/login',
          query: { redirect: router.resolve(reviewDestination(route)).fullPath },
        })
        return
      }
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
  <button v-if="!hideTrigger" type="button" class="review-open" @click="open">
    {{ triggerLabel || '写评价' }}
  </button>
  <dialog
    ref="dialog"
    class="review-dialog"
    :aria-labelledby="titleId"
    @cancel.prevent="requestClose"
  >
    <section v-if="receipt" class="publish-receipt" role="status">
      <h2 :id="titleId">{{ receipt.replayed ? '已确认此前发布成功' : '点评发布成功' }}</h2>
      <p class="receipt-target">{{ targetName }}</p>
      <button type="button" class="publish" @click="finish">完成</button>
    </section>
    <form v-else @submit.prevent="submit">
      <header class="composer-header">
        <h2 :id="titleId">写点评</h2>
        <button type="button" aria-label="关闭评价" :disabled="busy" @click="requestClose">
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="m6 6 12 12M18 6 6 18" />
          </svg>
        </button>
      </header>
      <div v-show="!locked" class="review-fields">
        <fieldset class="review-inputs" :disabled="busy || locked">
          <section class="target-section" aria-label="评价对象">
            <ReviewTargetSearch
              v-if="opened && !targetLocked"
              :selected="current"
              :disabled="busy || locked"
              @select="chooseRestaurant"
              @leave="close"
            />
            <div v-if="targetLocked" class="locked-target">
              <strong>{{ current?.name }}</strong>
            </div>
          </section>
          <section class="ratings-section" aria-label="四维评分">
            <div class="section-heading">
              <h3>四维评分</h3>
              <span class="review-average" :aria-label="`本条均分 ${total.toFixed(2)} 分`"
                ><b>{{ total.toFixed(2).replace(/0$/, '') }}</b> 分</span
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
              <button
                v-for="option in reviewRecommendations"
                :key="option"
                type="button"
                :aria-pressed="recommendation === option"
                @click="recommendation = option"
              >
                {{ recommendationLabels[option] }}
              </button>
            </div>
          </fieldset>
          <div class="section-heading body-heading">
            <label for="review-body">用餐体验</label>
          </div>
          <div class="body-editor">
            <textarea id="review-body" v-model="body" rows="4" /><span class="body-count"
              >{{ Array.from(body.trim()).length }} / 500</span
            >
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
            <div v-if="!photos.length" class="photo-empty">
              <button
                type="button"
                class="dropzone"
                aria-label="添加照片"
                :disabled="processing || !supportsPhotos"
                @click="photoInput?.click()"
              >
                <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path d="M12 5v14M5 12h14" />
                </svg>
              </button>
              <span class="photo-label">添加照片<small>最多 5 张</small></span>
            </div>
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
      <footer class="composer-footer">
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
          {{ busy ? '发布中…' : uploadingPhotos ? '照片上传中…' : '发布点评' }}
        </button>
      </footer>
    </form>
  </dialog>
</template>
<style scoped src="./review-composer.css"></style>
