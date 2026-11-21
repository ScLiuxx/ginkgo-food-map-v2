<script setup lang="ts">
import { createRequestId } from '@/shared/lib/createRequestId'
import AppLink from '@/shared/ui/AppLink.vue'
import LeaveConfirmation from '@/shared/ui/LeaveConfirmation.vue'
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { authToken, getAccessToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { useReturnNavigation } from '@/shared/lib/useReturnNavigation'
import { MerchantPlacePicker } from '@/modules/map'
import { compressPhoto } from '@/modules/file/model/photos'
import { useSubmissionPhotos } from '../model/useSubmissionPhotos'
import { useLoginStore } from '@/modules/auth'
import { fetchCurrentUser } from '@/modules/auth/api/authApi'
import { validBody } from '@/modules/review/model/review'
import { attachedReviewDraft } from '../model/attachedReview'
import AttachedReviewEditor from './AttachedReviewEditor.vue'
import {
  fetchMyRequestDetail,
  fetchMyRequestPhoto,
  fetchMyRequestReviewPhoto,
  editMyRequest,
  resubmitMyRequest,
  type MyRequestDetail,
  type MyRequestEditInput,
} from '../api/myRestaurantRequestsApi'
import {
  fetchCorrectionSource,
  fetchRestaurantCategories,
  submitRestaurantCorrection,
  submitNewRestaurant,
  fetchRestaurantManagement,
  createRestaurant,
  updateRestaurant,
} from '../api/restaurantApi'
import type {
  RestaurantCategory,
  RestaurantCorrectionInput,
  RestaurantResponse,
} from '../api/restaurantApi'
import {
  restaurantDraft,
  restaurantInput,
  validateRestaurantDraft,
  draftTags,
} from '../model/restaurantForm'
import type { RestaurantErrors } from '../model/restaurantForm'
import RestaurantAlbum from './RestaurantAlbum.vue'
import {
  readMerchantSubmission,
  type MerchantSubmissionPlace,
} from '../model/merchantSubmissionDraft'

/** 用户投稿与管理员直接维护共用门店搜索和表单，写入路径按显式模式区分。 */
const props = withDefaults(
  defineProps<{ id?: string; submission?: boolean; admin?: boolean; requestId?: string }>(),
  {
    id: '',
    submission: false,
    admin: false,
    requestId: '',
  },
)
const route = useRoute()
const router = useRouter()
const sourceRequest = ref<MyRequestDetail | null>(null)
const review = reactive(attachedReviewDraft())
const reviewError = ref('')
// 两步共用父组件草稿和图片队列，切页只切换视图，不能提前创建申请或清理照片。
const step = ref<'restaurant' | 'review'>('restaurant')
const heading = ref<globalThis.HTMLHeadingElement>()
const submittedReview = ref(false)
const isRequest = computed(() => !!props.requestId && !props.admin)
const isResubmit = computed(() => sourceRequest.value?.application.status === 'REJECTED')
const canAttachReview = computed(
  () =>
    !props.admin &&
    (isRequest.value
      ? sourceRequest.value?.application.requestType === 'CREATE'
      : props.submission) &&
    extra.restaurantType === 'REGULAR',
)
const reviewLocked = computed(() => !!sourceRequest.value?.reviewSubmission)
const pageTitle = computed(() =>
  isRequest.value
    ? isResubmit.value
      ? '修改并重新提交'
      : '修改申请'
    : props.admin
      ? props.submission
        ? '新增店铺'
        : '修改店铺'
      : props.submission
        ? '提交店铺'
        : '补充 / 纠错',
)
const token = getAccessToken()
const sessionChanged = ref(false)
const photoInput = ref<globalThis.HTMLInputElement>()
const processingPhotos = ref(false)
const photoError = ref('')
const photoDraft = useSubmissionPhotos('RESTAURANT', () => (sessionChanged.value ? null : token))
const reviewPhotos = useSubmissionPhotos('REVIEW', () => (sessionChanged.value ? null : token))
const reviewBusy = computed(
  () =>
    review.enabled &&
    (reviewPhotos.processing.value || reviewPhotos.pending.value || reviewPhotos.failed.value),
)
const { photos, pending: uploadingPhotos, failed: failedPhotos } = photoDraft
const form = reactive(restaurantDraft())
const extra = reactive<{
  restaurantType: 'REGULAR' | 'CANTEEN'
  dineIn: string
  delivery: string
}>({ restaurantType: 'REGULAR', dineIn: '', delivery: '' })
// 类型切换清除不适用的分类及错误；切回普通餐厅后需重新明确主营分类。
watch(
  () => extra.restaurantType,
  () => {
    if (loading.value) return
    form.categoryCode = ''
    delete errors.value.categoryCode
  },
  { flush: 'sync' },
)
const categories = ref<RestaurantCategory[]>([])
const originalCategory = ref('')
const loading = ref(false),
  saving = ref(false),
  loadError = ref(''),
  saveError = ref('')
const errors = ref<RestaurantErrors>({})
const receipt = ref('')
const selectedPlace = ref('')
const sourceName = ref('')
const content = computed(() =>
  JSON.stringify([
    form,
    extra,
    photos.value.map((p) => p.url),
    review,
    reviewPhotos.photos.value.map((p) => p.url),
  ]),
)
const baseline = ref(content.value)
const dirty = computed(() => content.value !== baseline.value)
let clientRequestId = createRequestId()
let lastSubmissionMode: boolean | undefined
let controller: globalThis.AbortController | undefined
let generation = 0,
  disposed = false
const path = computed(() =>
  isRequest.value
    ? route.path
    : props.admin
      ? route.fullPath
      : props.submission
        ? route.fullPath
        : `/restaurants/${encodeURIComponent(props.id)}/correction`,
)
const detailPath = computed(() =>
  isRequest.value
    ? '/account/contributions'
    : props.admin
      ? '/admin/restaurants'
      : props.submission
        ? '/'
        : `/restaurants/${encodeURIComponent(props.id)}`,
)
const { returnTarget, returnToPrevious } = useReturnNavigation(detailPath)
const visibleCategories = computed(() =>
  categories.value.filter((c) => c.enabled || c.code === originalCategory.value),
)

watch(
  content,
  () => {
    if (!saving.value) clientRequestId = createRequestId()
  },
  { flush: 'sync' },
)
watch(
  () => [form.latitude, form.longitude],
  () => {
    form.locationConfirmed = false
  },
  { flush: 'sync' },
)
watch(authToken, (value) => {
  if (value !== token) {
    sessionChanged.value = true
    controller?.abort()
    generation++
    photoDraft.clear(!!receipt.value)
    reviewPhotos.clear(!!receipt.value && submittedReview.value)
  }
})

/** 路由切换使旧读取失效，失败时不展示空白表单冒充已读取资料。 */
async function load() {
  if (!token || sessionChanged.value) return
  const ticket = ++generation
  photoDraft.clear(!!receipt.value)
  reviewPhotos.clear(!!receipt.value && submittedReview.value)
  Object.assign(review, attachedReviewDraft())
  step.value = 'restaurant'
  submittedReview.value = false
  lastSubmissionMode = undefined
  sourceRequest.value = null
  controller?.abort()
  controller = new globalThis.AbortController()
  loading.value = true
  loadError.value = ''
  receipt.value = ''
  selectedPlace.value = ''
  try {
    // 本人编辑始终重新读取归属和状态；不能直接相信路由参数或列表里的旧状态。
    let request: MyRequestDetail | null = null
    if (isRequest.value) {
      const auth = useLoginStore()
      // 刷新编辑页时全局身份恢复可能尚未完成，直接用固定凭证读取本人身份，不从令牌猜测ID。
      const user = auth.user ?? (await fetchCurrentUser(token))
      if (disposed || ticket !== generation) return
      request = await fetchMyRequestDetail(
        props.requestId,
        String(user.id),
        token,
        controller.signal,
      )
      if (
        !['PENDING', 'REJECTED'].includes(request.application.status) ||
        request.resubmittedRequestId
      )
        throw new Error('申请状态已变化，请返回我的投稿查看当前记录。')
      if (disposed || ticket !== generation) return
      sourceRequest.value = request
    }
    const [row, dictionary] = await Promise.all([
      request
        ? Promise.resolve({
            ...request.proposed,
            tags: Array.isArray(request.proposed.tags) ? request.proposed.tags : [],
          } as unknown as RestaurantResponse)
        : props.submission
          ? Promise.resolve(undefined)
          : props.admin
            ? fetchRestaurantManagement(props.id, token, controller.signal).then((snapshot) => {
                if (snapshot.deleted) throw new Error('店铺已删除，请先在商户管理中恢复。')
                return snapshot.restaurant
              })
            : fetchCorrectionSource(props.id, controller.signal),
      fetchRestaurantCategories(controller.signal),
    ])
    if (disposed || ticket !== generation) return
    sourceName.value = row?.name ?? ''
    Object.assign(form, restaurantDraft(row))
    if (props.submission && !isRequest.value) {
      form.name =
        typeof route.query.keyword === 'string'
          ? Array.from(route.query.keyword).slice(0, 40).join('')
          : ''
      const draft =
        typeof route.query.draft === 'string'
          ? readMerchantSubmission(route.query.draft)
          : undefined
      if (draft) selectPlace(draft)
    }
    Object.assign(extra, {
      restaurantType: row?.restaurantType ?? 'REGULAR',
      dineIn: row?.supportsDineIn == null ? '' : String(row.supportsDineIn),
      delivery: row?.supportsDelivery == null ? '' : String(row.supportsDelivery),
    })
    if (extra.restaurantType === 'CANTEEN') form.categoryCode = ''
    categories.value = dictionary
    originalCategory.value = row?.categoryCode ?? ''
    if (request) {
      form.locationConfirmed = request.proposed.locationConfirmed === true
      const attached = request.reviewSubmission
      if (attached && attached.status !== 'PUBLISHED' && attached.scores) {
        Object.assign(review, {
          enabled: true,
          scores: { ...attached.scores },
          recommendation: attached.recommendation,
          body: attached.body,
        })
      }
      const requestId = props.requestId,
        signal = controller.signal
      await Promise.all([
        photoDraft.copyExisting(request.photoPositions, (position) =>
          fetchMyRequestPhoto(requestId, position, token, signal),
        ),
        reviewPhotos.copyExisting(attached?.photoPositions ?? [], (position) =>
          fetchMyRequestReviewPhoto(requestId, position, token, signal),
        ),
      ])
      if (disposed || ticket !== generation) return
    }
    baseline.value = content.value
    errors.value = {}
    saveError.value = ''
  } catch (error) {
    if (ticket === generation && !controller.signal.aborted) loadError.value = toErrorMessage(error)
  } finally {
    if (ticket === generation) loading.value = false
  }
}
watch(
  () => [props.id, props.submission, props.requestId, route.query.draft, route.query.keyword],
  () => void load(),
  { immediate: true },
)

/** 用户只能选门店更换位置；高德坐标直接使用GCJ-02，精度规范到业务支持的六位。 */
function selectPlace(place: MerchantSubmissionPlace) {
  if (saving.value || sessionChanged.value) return
  form.name = place.name
  form.address = place.address
  form.latitude = place.latitude.toFixed(6)
  form.longitude = place.longitude.toFixed(6)
  form.locationConfirmed = true
  selectedPlace.value = place.name
  errors.value = {}
}

/** 两步共用店铺校验；进入点评页只校验，不调用任何申请写入接口。 */
function validateShop() {
  if (processingPhotos.value || uploadingPhotos.value || failedPhotos.value) {
    photoError.value = '请等待图片上传完成，失败的图片可重试或移除。'
    return false
  }
  errors.value = validateRestaurantDraft(form, extra.restaurantType)
  const category = categories.value.find((c) => c.code === form.categoryCode)
  if (
    extra.restaurantType === 'REGULAR' &&
    (!category || (!category.enabled && category.code !== originalCategory.value))
  )
    errors.value.categoryCode = '请选择可用分类。'
  return !Object.keys(errors.value).length
}

async function changeStep(value: 'restaurant' | 'review') {
  if (saving.value || sessionChanged.value) return
  step.value = value
  saveError.value = ''
  await nextTick()
  heading.value?.focus({ preventScroll: true })
  heading.value?.scrollIntoView?.({ block: 'start' })
}

function nextStep() {
  if (!token || saving.value || sessionChanged.value || getAccessToken() !== token) return
  if (!canAttachReview.value || !validateShop()) return
  review.enabled = true
  void changeStep('review')
}

/** 第一步按回车等同“下一步”，仅提交店铺必须明确点击对应按钮。 */
function submitCurrentStep() {
  if (canAttachReview.value && step.value === 'restaurant') nextStep()
  else void submit(step.value === 'review')
}

/** 固定账号与同次请求ID；区分明确的仅店铺／联合提交意图，避免重试摘要冲突。 */
async function submit(includeReview = false) {
  if (!token || saving.value || sessionChanged.value || getAccessToken() !== token) return
  if (reviewLocked.value && !includeReview) {
    nextStep()
    return
  }
  if (!validateShop()) {
    if (step.value === 'review') void changeStep('restaurant')
    return
  }
  reviewError.value = ''
  if (includeReview) {
    if (!canAttachReview.value || !review.recommendation || !validBody(review.body, 1, 500))
      reviewError.value = '请选择推荐态度并填写点评正文，最多500字。'
    else if (reviewBusy.value)
      reviewError.value = '请等待点评图片上传完成，失败的图片可重试或移除。'
    if (reviewError.value) return
  }
  if (lastSubmissionMode !== undefined && lastSubmissionMode !== includeReview)
    clientRequestId = createRequestId()
  lastSubmissionMode = includeReview
  saving.value = true
  saveError.value = ''
  try {
    // 管理员直接保存完整业务资料，三态显式提交；不附带投稿UUID或未支持的图片ID。
    if (props.admin) {
      const input = {
        ...restaurantInput(form, extra.restaurantType),
        restaurantType: extra.restaurantType,
        supportsDineIn: extra.dineIn === '' ? null : extra.dineIn === 'true',
        supportsDelivery: extra.delivery === '' ? null : extra.delivery === 'true',
      }
      const result = props.submission
        ? await createRestaurant(input, token)
        : await updateRestaurant(props.id, input, token)
      if (!disposed && getAccessToken() === token && !sessionChanged.value) {
        receipt.value = result.restaurant.id
        await router.replace({ path: '/admin/restaurants', query: { saved: result.restaurant.id } })
      }
      return
    }
    const input: RestaurantCorrectionInput = {
      ...restaurantInput(form, extra.restaurantType),
      clientRequestId,
      ...(photos.value.length ? { photoIds: photos.value.map((p) => p.fileId!) } : {}),
      restaurantType: extra.restaurantType,
      supportsDineIn: extra.dineIn === '' ? null : extra.dineIn === 'true',
      supportsDelivery: extra.delivery === '' ? null : extra.delivery === 'true',
      ...(includeReview && canAttachReview.value
        ? {
            review: {
              scores: { ...review.scores },
              recommendation: review.recommendation!,
              body: review.body.trim(),
              photoIds: reviewPhotos.photos.value.map((p) => p.fileId!),
            },
          }
        : {}),
    }
    const editInput: MyRequestEditInput & { clientRequestId?: string } = { ...input }
    delete editInput.clientRequestId
    const result = isRequest.value
      ? isResubmit.value
        ? await resubmitMyRequest(props.requestId, input, token)
        : await editMyRequest(props.requestId, editInput, token)
      : props.submission
        ? await submitNewRestaurant(input, token)
        : await submitRestaurantCorrection(props.id, input, token)
    if (!disposed && getAccessToken() === token && !sessionChanged.value) {
      receipt.value = result.id
      submittedReview.value = includeReview
      photoDraft.clear(true)
      // 仅提交店铺时，点评临时图没有绑定，应取消；联合成功只释放预览，保护已绑定图。
      reviewPhotos.clear(includeReview)
      await nextTick()
      heading.value?.focus()
    }
  } catch (error) {
    if (!disposed && getAccessToken() === token)
      saveError.value = props.admin
        ? `${toErrorMessage(error)}。若请求超时，请先返回商户管理核对保存结果，避免重复新增。`
        : toErrorMessage(error)
  } finally {
    saving.value = false
  }
}
/** 压缩后上传，过量明确拒绝；异步解码完成时重新核对路由及账号，避免旧页回填。 */
async function addPhotos(files: globalThis.FileList | null) {
  if (!files || processingPhotos.value || saving.value || sessionChanged.value) return
  photoError.value = ''
  if (photos.value.length + files.length > 5) {
    photoError.value = '最多添加5张图片，请先移除多余图片。'
    return
  }
  const ticket = generation
  processingPhotos.value = true
  try {
    for (const file of Array.from(files)) {
      const compressed = await compressPhoto(file)
      if (disposed || ticket !== generation || getAccessToken() !== token) return
      photoDraft.add(compressed)
    }
  } catch (error) {
    if (!disposed && ticket === generation) photoError.value = toErrorMessage(error)
  } finally {
    processingPhotos.value = false
  }
}
const leaveConfirmation = ref<InstanceType<typeof LeaveConfirmation> | null>(null)
function canLeave() {
  return (
    !!receipt.value ||
    (!saving.value &&
      ((!dirty.value && !processingPhotos.value && !reviewPhotos.processing.value) ||
        leaveConfirmation.value?.ask()))
  )
}
onBeforeRouteLeave(canLeave)
onBeforeRouteUpdate(canLeave)
function beforeUnload(event: globalThis.BeforeUnloadEvent) {
  if (
    !receipt.value &&
    (dirty.value || saving.value || processingPhotos.value || reviewPhotos.processing.value)
  ) {
    event.preventDefault()
    event.returnValue = ''
  }
}
globalThis.addEventListener('beforeunload', beforeUnload)
onBeforeUnmount(() => {
  disposed = true
  generation++
  controller?.abort()
  photoDraft.clear(!!receipt.value)
  reviewPhotos.clear(!!receipt.value && submittedReview.value)
  globalThis.removeEventListener('beforeunload', beforeUnload)
})
</script>

<template>
  <section
    class="correction-page"
    :class="{
      'submission-page': submission && !admin,
      'receipt-page': receipt && !sessionChanged,
    }"
  >
    <LeaveConfirmation ref="leaveConfirmation" message="资料尚未提交，确定离开？" />
    <AppLink
      v-if="!receipt || sessionChanged"
      :to="admin ? detailPath : returnTarget"
      aria-label="返回"
      @click.capture="!admin && returnToPrevious($event)"
      ><span aria-hidden="true">←</span></AppLink
    >
    <p v-if="submission && !admin && !receipt" class="submission-eyebrow">一起补全这张美食地图</p>
    <h1 v-if="!receipt || sessionChanged" ref="heading" tabindex="-1">
      {{ step === 'review' && !receipt ? '写一条点评' : pageTitle }}
    </h1>
    <section v-if="!token || sessionChanged" class="notice" role="alert">
      <p>
        {{
          sessionChanged
            ? '登录账号已变化，请重新打开此页后继续。'
            : submission
              ? '登录后可提交新店铺，管理员审核通过后公开。'
              : '登录后可为这家商户补充资料或提出纠错。'
        }}
      </p>
      <AppLink :to="{ path: '/login', query: { redirect: path } }">前往登录</AppLink>
    </section>
    <p v-else-if="loading" role="status">正在读取商户资料…</p>
    <section v-else-if="loadError" class="notice" role="alert">
      <p>{{ loadError }}</p>
      <button @click="load">重新加载</button>
    </section>
    <section v-else-if="receipt" class="submission-receipt" aria-labelledby="receipt-title">
      <div class="receipt-mark" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none">
          <path
            d="m6 12 4 4 8-8"
            stroke="currentColor"
            stroke-width="2"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
      </div>
      <h1 id="receipt-title" ref="heading" tabindex="-1">
        {{ admin ? '店铺资料已保存' : '已提交，等待审核' }}
      </h1>
      <p class="receipt-name">{{ form.name }}</p>
      <p v-if="submittedReview" class="receipt-review">附带点评已保存，审核通过后自动发布。</p>
      <div class="receipt-actions">
        <AppLink
          :to="admin ? detailPath : returnTarget"
          class="receipt-done"
          @click.capture="!admin && returnToPrevious($event)"
          >完成</AppLink
        >
        <AppLink
          v-if="!admin"
          class="receipt-detail"
          :to="{ path: '/account/contributions', query: { requestId: receipt } }"
          >查看申请</AppLink
        >
      </div>
    </section>
    <form v-else novalidate :aria-busy="saving" @submit.prevent="submitCurrentStep">
      <ol v-if="canAttachReview" class="submission-steps" aria-label="投稿步骤">
        <li :aria-current="step === 'restaurant' ? 'step' : undefined"><span>1</span>店铺资料</li>
        <li :aria-current="step === 'review' ? 'step' : undefined">
          <span>2</span>写点评 <small>选填</small>
        </li>
      </ol>
      <p v-if="sourceRequest?.rejectReason" class="notice">
        驳回原因：{{ sourceRequest.rejectReason }}
      </p>
      <p v-show="step === 'restaurant'" class="intro">
        {{
          admin
            ? '搜索并选择高德门店，核对资料后直接保存。'
            : submission
              ? '请搜索并选择门店，补充资料后提交审核。'
              : '已填入当前商户资料，只需修改有误或缺失的内容。'
        }}
      </p>
      <div class="submission-layout">
        <div class="submission-main">
          <div v-show="step === 'restaurant'" class="restaurant-fields">
            <fieldset :disabled="saving">
              <legend>商户资料</legend>
              <MerchantPlacePicker
                :key="id"
                v-model="form.name"
                :disabled="saving"
                :invalid="!!errors.name"
                :name-error="errors.name"
                @select="selectPlace"
              />
              <div class="fields">
                <label v-if="extra.restaurantType === 'REGULAR'"
                  >美食品类<select
                    v-model="form.categoryCode"
                    :aria-invalid="!!errors.categoryCode"
                  >
                    <option value="">请选择分类</option>
                    <option v-for="c in visibleCategories" :key="c.code" :value="c.code">
                      {{ c.name }}
                    </option></select
                  ><small>{{ errors.categoryCode }}</small></label
                >
                <label
                  >餐厅类型<select v-model="extra.restaurantType">
                    <option value="REGULAR">普通餐厅</option>
                    <option value="CANTEEN" :disabled="reviewLocked">食堂</option>
                  </select></label
                >
                <label
                  >人均类型<select v-model="form.priceKind">
                    <option value="UNKNOWN">未知</option>
                    <option value="EXACT">填报具体金额</option>
                    <option value="ESTIMATE">估值</option>
                  </select></label
                >
                <label
                  >人均消费（元）<input
                    v-model="form.price"
                    :disabled="form.priceKind === 'UNKNOWN'"
                    inputmode="numeric"
                    :aria-invalid="!!errors.price"
                  /><small>{{ errors.price || '1–5000 元，未知时可不填' }}</small></label
                >
                <label
                  >支持堂食<select v-model="extra.dineIn">
                    <option value="">未知</option>
                    <option value="true">支持</option>
                    <option value="false">不支持</option>
                  </select></label
                >
                <label
                  >支持外卖<select v-model="extra.delivery">
                    <option value="">未知</option>
                    <option value="true">支持</option>
                    <option value="false">不支持</option>
                  </select></label
                >
              </div>
              <label
                >地址<input
                  v-model="form.address"
                  maxlength="80"
                  :aria-invalid="!!errors.address"
                /><small>{{ errors.address }}</small></label
              >
              <label
                >经营状态<select v-model="form.businessStatus">
                  <option value="OPERATING">正常经营</option>
                  <option value="CLOSED">停业</option>
                </select></label
              >
            </fieldset>
            <fieldset :disabled="saving">
              <legend>商户位置</legend>
              <p v-if="selectedPlace" class="notice" role="status">
                已选择：{{ selectedPlace }}，商户位置已自动更新。
              </p>
              <label v-else-if="form.latitude && form.longitude" class="check"
                ><input
                  v-model="form.locationConfirmed"
                  type="checkbox"
                />我已核对并确认商户位置</label
              >
              <p v-else class="intro">这家商户还没有位置信息，请搜索并选择门店。</p>
              <small class="error">{{
                errors.latitude || errors.longitude
                  ? '请通过高德搜索并选择有效门店。'
                  : errors.locationConfirmed
              }}</small>
            </fieldset>
            <!-- 已公开相册独立只读回显，不进入新上传草稿、五张上限、清理队列或提交photoIds。 -->
            <fieldset v-if="!submission && !requestId" class="existing-merchant-photos">
              <legend>当前商户照片</legend>
              <p class="intro">
                {{
                  admin
                    ? '以下为已公开的商户照片，可点击查看大图。'
                    : '以下为已公开的商户照片，可点击查看大图。已有照片无需重复上传；本次新增照片审核通过后追加到相册。'
                }}
              </p>
              <RestaurantAlbum :key="id" :restaurant-id="id" :name="sourceName" />
            </fieldset>
            <!-- 新图片使用本地Object URL预览；上传文件在申请通过审核前保持私有。 -->
            <fieldset v-if="!admin" :disabled="saving">
              <legend>{{ submission ? '商户图片' : '本次新增图片' }}</legend>
              <input
                ref="photoInput"
                class="photo-input"
                type="file"
                accept="image/jpeg,image/png,image/webp"
                multiple
                :disabled="processingPhotos || saving || sessionChanged"
                aria-label="选择商户图片"
                @change="
                  addPhotos(($event.target as HTMLInputElement).files)
                  ;($event.target as HTMLInputElement).value = ''
                "
              />
              <div class="merchant-photos">
                <div v-for="(photo, index) in photos" :key="photo.url" class="merchant-photo">
                  <div class="merchant-photo-preview">
                    <img :src="photo.url" :alt="`待审商户图片 ${index + 1}`" />
                    <button
                      type="button"
                      class="photo-remove"
                      :aria-label="`移除第${index + 1}张图片`"
                      @click="photoDraft.remove(index)"
                    >
                      ×
                    </button>
                  </div>
                  <p role="status" class="intro">
                    {{
                      photo.state === 'uploading'
                        ? '上传中…'
                        : photo.state === 'uploaded'
                          ? '已上传，提交后待审核'
                          : photo.error
                    }}
                  </p>
                  <button
                    v-if="photo.state === 'failed'"
                    type="button"
                    class="photo-retry"
                    @click="photoDraft.retry(photo)"
                  >
                    重试上传
                  </button>
                </div>
                <button
                  v-if="photos.length < 5"
                  type="button"
                  class="photo-upload-placeholder"
                  :disabled="processingPhotos || saving || sessionChanged"
                  aria-label="添加商户图片"
                  aria-describedby="merchant-photo-hint"
                  @click="photoInput?.click()"
                >
                  <svg viewBox="0 0 32 32" fill="none" aria-hidden="true">
                    <path
                      d="M16 6v20M6 16h20"
                      stroke="currentColor"
                      stroke-width="1.5"
                      stroke-linecap="round"
                    />
                  </svg>
                </button>
              </div>
              <p id="merchant-photo-hint" class="intro">
                {{
                  processingPhotos
                    ? '正在处理图片…'
                    : '最多5张，支持JPEG、PNG、WebP，自动压缩。提交后等待审核，审核通过后公开。'
                }}
              </p>
              <p v-if="photoError" role="alert" class="error">{{ photoError }}</p>
            </fieldset>
            <fieldset :disabled="saving">
              <legend>商户标签</legend>
              <label
                >每行一个标签<textarea
                  v-model="form.tags"
                  rows="4"
                  :aria-invalid="!!errors.tags"
                ></textarea></label
              ><small>{{
                errors.tags || `最多8项、总计80字；当前${draftTags(form.tags).length}项`
              }}</small>
            </fieldset>
            <p v-if="submission && !admin && extra.restaurantType === 'CANTEEN'" class="intro">
              食堂需要评价具体窗口，收录后可前往窗口写点评。
            </p>
          </div>
          <section v-if="step === 'review'" class="review-step">
            <div class="shop-summary" aria-label="点评对应的店铺">
              <small>这次点评的店铺</small>
              <h2>{{ form.name }}</h2>
              <p>{{ form.address }}</p>
              <button type="button" :disabled="saving" @click="changeStep('restaurant')">
                修改店铺资料
              </button>
            </div>
            <AttachedReviewEditor
              :draft="review"
              :photos="reviewPhotos"
              :locked="reviewLocked"
              :disabled="saving || sessionChanged"
              :error="reviewError"
              @change="Object.assign(review, $event)"
            />
          </section>
        </div>
      </div>
      <p v-if="step === 'restaurant'" class="notice">
        {{
          admin
            ? '保存后立即更新公开资料。食堂不设置美食品类；已有窗口的食堂不能直接改为普通餐厅。'
            : '审核通过后更新公开资料。'
        }}
      </p>
      <p v-if="Object.keys(errors).length" role="alert" class="error">请修改标记的字段后再提交。</p>
      <p v-if="saveError" role="alert" class="error">
        {{ saveError }}{{ admin ? '' : '，资料已保留，可重试提交。' }}
      </p>
      <p
        v-if="step === 'restaurant' && canAttachReview && review.enabled && !reviewLocked"
        class="intro"
      >
        已填写的点评和照片仍保留，可继续编辑；选择“仅提交店铺”不会保存这条点评。
      </p>
      <div v-if="canAttachReview" class="actions step-actions">
        <small class="submission-summary">{{
          step === 'review'
            ? '店铺与点评将一起提交，审核通过后自动发布点评。'
            : reviewLocked
              ? '此申请已有点评，请到下一步核对后一起保存。'
              : '去过这家店？可以顺便分享用餐体验。'
        }}</small>
        <button
          v-if="step === 'review'"
          type="button"
          class="secondary"
          :disabled="saving"
          @click="changeStep('restaurant')"
        >
          ← 返回店铺资料
        </button>
        <button
          v-else-if="!reviewLocked"
          type="button"
          class="secondary"
          :disabled="saving || processingPhotos || uploadingPhotos || failedPhotos"
          @click="submit(false)"
        >
          仅提交店铺
        </button>
        <button
          type="submit"
          :disabled="
            saving ||
            processingPhotos ||
            uploadingPhotos ||
            failedPhotos ||
            (step === 'review' && reviewBusy)
          "
        >
          {{
            saving
              ? '正在提交…'
              : step === 'restaurant'
                ? '下一步，写点评 →'
                : requestId
                  ? isResubmit
                    ? '重新提交店铺与点评'
                    : '保存店铺与点评'
                  : '提交店铺与点评'
          }}
        </button>
      </div>
      <div v-else class="actions">
        <button
          type="submit"
          :disabled="saving || processingPhotos || uploadingPhotos || failedPhotos"
        >
          {{
            saving
              ? admin
                ? '正在保存…'
                : '正在提交…'
              : admin
                ? submission
                  ? '创建店铺'
                  : '保存修改'
                : requestId
                  ? isResubmit
                    ? '重新提交申请'
                    : '保存修改'
                  : '提交审核'
          }}</button
        ><AppLink :to="detailPath">取消</AppLink>
      </div>
    </form>
  </section>
</template>

<style scoped>
.correction-page {
  width: min(820px, calc(100% - 32px));
  margin: 30px auto 100px;
  color: #30343b;
}
/* 投稿与本人编辑共用居中单栏，限制表单宽度，避免移除侧栏后留下空白或拉长输入项。 */
.submission-page {
  width: min(760px, calc(100% - 40px));
}
.submission-eyebrow {
  color: #af8251;
  font-size: 11px;
  letter-spacing: 2px;
  margin: 28px 0 10px;
}
.submission-page h1 {
  margin: 0 0 20px;
  font-size: 29px;
}
.submission-page form {
  border: 0;
  padding: 0;
  border-radius: 0;
}
.submission-main {
  min-width: 0;
}
.submission-page .restaurant-fields > fieldset {
  border: 1px solid #e9ece8;
  border-radius: 12px;
  padding: 22px 24px;
  margin: 24px 0;
  background: #fff;
}
.submission-page legend {
  font-size: 16px;
  padding: 0 8px;
}
.submission-page :is(input:not([type='checkbox']), select, textarea) {
  background: #fff;
  border-color: #dfe4e5;
  border-radius: 6px;
  font-size: 13px;
}
.submission-page label {
  font-size: 12px;
  color: #5a656d;
}
.submission-page .actions {
  border-top: 1px solid #eceeea;
  padding: 20px 0;
  flex-wrap: wrap;
}
.submission-summary {
  margin-right: auto;
}
.submission-page .actions > button {
  background: #ed740a;
  border-color: #ed740a;
}
.submission-page .actions > .secondary {
  background: #fff;
  border-color: #dfe4e5;
  color: #58636b;
}
.submission-steps {
  display: flex;
  align-items: center;
  gap: 28px;
  padding: 0 0 18px;
  margin: 0 0 20px;
  border-bottom: 1px solid #eceeea;
  list-style: none;
  color: #8a9399;
  font-size: 13px;
}
.submission-steps li {
  display: flex;
  align-items: center;
  gap: 8px;
}
.submission-steps li > span {
  display: grid;
  place-items: center;
  width: 25px;
  height: 25px;
  border-radius: 50%;
  background: #edf0ed;
}
.submission-steps [aria-current='step'] {
  color: #bd6413;
  font-weight: 600;
}
.submission-steps [aria-current='step'] > span {
  background: #fff0dd;
}
.shop-summary {
  position: relative;
  padding: 20px 24px;
  border: 1px solid #e9ece8;
  border-radius: 12px;
  background: #fafbf8;
  overflow-wrap: anywhere;
}
.shop-summary h2 {
  margin: 8px 0;
  font-size: 18px;
}
.shop-summary p {
  margin: 0;
  font-size: 12px;
  color: #737b84;
}
.shop-summary button {
  background: transparent;
  border: 0;
  color: #ad6f26;
  padding: 12px 0 0;
  font-size: 12px;
}
.step-actions .submission-summary {
  flex-basis: 100%;
}
@media (max-width: 760px) {
  .submission-page .restaurant-fields > fieldset {
    padding: 18px;
  }
  .submission-page .actions {
    position: sticky;
    bottom: 58px;
    background: #fffffff7;
    backdrop-filter: blur(8px);
    gap: 12px;
    z-index: 2;
  }
  .submission-summary {
    flex-basis: 100%;
    font-size: 11px;
  }
}
a {
  color: #936021;
  text-decoration: none;
}
h1 {
  font-size: 32px;
  margin: 26px 0;
}
form {
  padding: 26px;
  border: 1px solid #e7e7e7;
  border-radius: 16px;
  background: white;
}
.intro,
small {
  color: #737b84;
  font-size: 12px;
  line-height: 1.7;
}
fieldset {
  min-width: 0;
  margin: 22px 0;
  padding: 20px 0 0;
  border: 0;
  border-top: 1px solid #eee;
}
legend {
  font-weight: 650;
  padding-right: 15px;
}
.fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 22px;
}
label {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 14px;
  margin: 12px 0;
}
input:not([type='checkbox']),
select,
textarea {
  width: 100%;
  min-width: 0;
  min-height: 44px;
  box-sizing: border-box;
  padding: 10px 12px;
  border: 1px solid #ddd;
  border-radius: 8px;
  background: #fafafa;
  color: inherit;
  font: inherit;
}
textarea {
  resize: vertical;
}
.check {
  flex-direction: row;
  align-items: center;
}
.check input {
  width: 18px;
  height: 18px;
}
.notice {
  padding: 14px 16px;
  background: #fff8eb;
  border-radius: 10px;
  line-height: 1.7;
  font-size: 14px;
}
.error,
[aria-invalid='true'] + small {
  color: #b64e37;
}
[aria-invalid='true'] {
  border-color: #b64e37 !important;
}
.actions {
  display: flex;
  align-items: center;
  gap: 22px;
  margin-top: 24px;
}
button {
  font: inherit;
  cursor: pointer;
  padding: 10px 18px;
  border: 1px solid #e0a258;
  border-radius: 8px;
  background: #ed982d;
  color: white;
}
/* 独立于提交按钮样式，保持正方形加号框在手机和电脑上均清晰可见。 */
.photo-upload-placeholder {
  display: grid;
  place-items: center;
  width: 120px;
  height: 120px;
  margin-top: 12px;
  padding: 0;
  border: 1px dashed #cdd1d6;
  border-radius: 10px;
  background: #fafafa;
  color: #969da5;
}
.photo-upload-placeholder svg {
  width: 32px;
  height: 32px;
}
.photo-upload-placeholder:disabled {
  opacity: 0.6;
  cursor: default;
}
.photo-upload-placeholder:not(:disabled):hover {
  border-color: #d59a4d;
  color: #ad6f26;
  background: #fffaf2;
}
.photo-input {
  display: none;
}
.merchant-photos {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 16px;
  margin-top: 12px;
}
.merchant-photos .photo-upload-placeholder {
  margin-top: 0;
  flex: 0 0 120px;
}
.merchant-photo {
  width: 120px;
  overflow-wrap: anywhere;
}
.merchant-photo-preview {
  width: 120px;
  height: 120px;
  position: relative;
}
.merchant-photo img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 10px;
}
.photo-remove {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: #30343bcc;
  font-size: 22px;
}
.photo-retry {
  padding: 6px 10px;
  font-size: 12px;
}
button:disabled:not(.photo-upload-placeholder),
fieldset:disabled {
  opacity: 0.6;
}
:is(input, select, textarea, button, a):focus-visible {
  outline: 2px solid #ad6f26;
  outline-offset: 3px;
}
.correction-page.receipt-page {
  width: min(520px, calc(100% - 40px));
  margin-top: clamp(48px, 12vh, 112px);
}
.submission-receipt {
  padding: 36px 24px;
  border-radius: 20px;
  background: #fff;
  text-align: center;
}
.receipt-mark {
  display: grid;
  place-items: center;
  width: 56px;
  height: 56px;
  margin: 0 auto 22px;
  border-radius: 50%;
  background: #fff3e8;
  color: #e77824;
}
.receipt-mark svg {
  width: 30px;
  height: 30px;
}
.submission-receipt h1 {
  margin: 0;
  color: #30363d;
  font-size: clamp(21px, 4.5vw, 25px);
  line-height: 1.4;
  outline: none;
}
.receipt-name {
  margin: 12px 0 0;
  color: #737b84;
  font-size: 15px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.receipt-review {
  margin: 18px 0 0;
  color: #858c94;
  font-size: 13px;
  line-height: 1.8;
}
.receipt-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  align-items: center;
  gap: 12px 24px;
  margin-top: 28px;
}
.receipt-actions a {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  box-sizing: border-box;
  font-size: 14px;
}
.receipt-done {
  min-width: 112px;
  padding: 10px 24px;
  border-radius: 10px;
  background: #ed740a;
  color: #fff;
}
.receipt-done:hover {
  background: #d96608;
}
.receipt-detail {
  color: #737b84;
}
.receipt-detail:hover {
  color: #d96608;
}
.receipt-actions a:focus-visible {
  outline-color: #a5adb7;
}
@media (max-width: 560px) {
  .fields {
    grid-template-columns: 1fr;
  }
  form {
    padding: 18px;
  }
  h1 {
    font-size: 28px;
  }
}
</style>
