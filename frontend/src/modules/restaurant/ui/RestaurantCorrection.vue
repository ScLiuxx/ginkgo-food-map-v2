<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute } from 'vue-router'
import { authToken, getAccessToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { MerchantPlacePicker } from '@/modules/map'
import { compressPhoto } from '@/modules/file/model/photos'
import { useDraftPhotos } from '@/modules/file/model/useDraftPhotos'
import { uploadImage, cancelImage } from '@/modules/file/api/mediaApi'
import {
  fetchCorrectionSource,
  fetchRestaurantCategories,
  submitRestaurantCorrection,
  submitNewRestaurant,
} from '../api/restaurantApi'
import type { RestaurantCategory, RestaurantCorrectionInput } from '../api/restaurantApi'
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

/** 新店投稿与资料纠错共用表单，只提交待审核申请；归属管理沿用独立页面。 */
const props = withDefaults(defineProps<{ id?: string; submission?: boolean }>(), {
  id: '',
  submission: false,
})
const route = useRoute()
const pageTitle = computed(() => (props.submission ? '提交店铺' : '补充 / 纠错'))
const token = getAccessToken()
const sessionChanged = ref(false)
const photoInput = ref<globalThis.HTMLInputElement>()
const processingPhotos = ref(false)
const photoError = ref('')
const photoDraft = useDraftPhotos(
  {
    uploadPhoto: (file, owner) => uploadImage(file, owner, 'RESTAURANT'),
    cancelPhoto: cancelImage,
  },
  () => (sessionChanged.value ? null : token),
)
const { photos, pending: uploadingPhotos, failed: failedPhotos } = photoDraft
const form = reactive(restaurantDraft())
const extra = reactive<{
  restaurantType: 'REGULAR' | 'CANTEEN'
  dineIn: string
  delivery: string
}>({ restaurantType: 'REGULAR', dineIn: '', delivery: '' })
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
const content = computed(() => JSON.stringify([form, extra, photos.value.map((p) => p.url)]))
const baseline = ref(content.value)
const dirty = computed(() => content.value !== baseline.value)
let clientRequestId = globalThis.crypto.randomUUID()
let controller: globalThis.AbortController | undefined
let generation = 0,
  disposed = false
const path = computed(() =>
  props.submission ? route.fullPath : `/restaurants/${encodeURIComponent(props.id)}/correction`,
)
const detailPath = computed(() =>
  props.submission ? '/' : `/restaurants/${encodeURIComponent(props.id)}`,
)
const visibleCategories = computed(() =>
  categories.value.filter((c) => c.enabled || c.code === originalCategory.value),
)

watch(
  content,
  () => {
    if (!saving.value) clientRequestId = globalThis.crypto.randomUUID()
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
  }
})

/** 路由切换使旧读取失效，失败时不展示空白表单冒充已读取资料。 */
async function load() {
  if (!token || sessionChanged.value) return
  const ticket = ++generation
  photoDraft.clear(!!receipt.value)
  controller?.abort()
  controller = new globalThis.AbortController()
  loading.value = true
  loadError.value = ''
  receipt.value = ''
  selectedPlace.value = ''
  try {
    const [row, dictionary] = await Promise.all([
      props.submission
        ? Promise.resolve(undefined)
        : fetchCorrectionSource(props.id, controller.signal),
      fetchRestaurantCategories(controller.signal),
    ])
    if (disposed || ticket !== generation) return
    sourceName.value = row?.name ?? ''
    Object.assign(form, restaurantDraft(row))
    if (props.submission) {
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
    categories.value = dictionary
    originalCategory.value = row?.categoryCode ?? ''
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
  () => [props.id, props.submission, route.query.draft, route.query.keyword],
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

/** 固定账号并保留同次请求ID；成功仅显示待审回执，不刷新或覆盖公开资料。 */
async function submit() {
  if (!token || saving.value || sessionChanged.value || getAccessToken() !== token) return
  if (processingPhotos.value || uploadingPhotos.value || failedPhotos.value) {
    photoError.value = '请等待图片上传完成，失败的图片可重试或移除。'
    return
  }
  errors.value = validateRestaurantDraft(form)
  const category = categories.value.find((c) => c.code === form.categoryCode)
  if (!category || (!category.enabled && category.code !== originalCategory.value))
    errors.value.categoryCode = '请选择可用分类。'
  if (Object.keys(errors.value).length) return
  saving.value = true
  saveError.value = ''
  try {
    const input: RestaurantCorrectionInput = {
      ...restaurantInput(form),
      clientRequestId,
      ...(photos.value.length ? { photoIds: photos.value.map((p) => p.fileId!) } : {}),
      restaurantType: extra.restaurantType,
      supportsDineIn: extra.dineIn === '' ? null : extra.dineIn === 'true',
      supportsDelivery: extra.delivery === '' ? null : extra.delivery === 'true',
    }
    const result = props.submission
      ? await submitNewRestaurant(input, token)
      : await submitRestaurantCorrection(props.id, input, token)
    if (!disposed && getAccessToken() === token && !sessionChanged.value) {
      receipt.value = result.id
      photoDraft.clear(true)
    }
  } catch (error) {
    if (!disposed) saveError.value = toErrorMessage(error)
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
function canLeave() {
  return (
    !!receipt.value ||
    (!saving.value &&
      ((!dirty.value && !processingPhotos.value) ||
        globalThis.confirm('资料尚未提交，确定离开吗？')))
  )
}
onBeforeRouteLeave(canLeave)
onBeforeRouteUpdate(canLeave)
function beforeUnload(event: globalThis.BeforeUnloadEvent) {
  if (!receipt.value && (dirty.value || saving.value || processingPhotos.value)) {
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
  globalThis.removeEventListener('beforeunload', beforeUnload)
})
</script>

<template>
  <section class="correction-page">
    <RouterLink :to="detailPath">{{ submission ? '← 返回首页' : '← 返回商户详情' }}</RouterLink>
    <h1>{{ pageTitle }}</h1>
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
      <RouterLink :to="{ path: '/login', query: { redirect: path } }">前往登录</RouterLink>
    </section>
    <p v-else-if="loading" role="status">正在读取商户资料…</p>
    <section v-else-if="loadError" class="notice" role="alert">
      <p>{{ loadError }}</p>
      <button @click="load">重新加载</button>
    </section>
    <section v-else-if="receipt" class="notice success" role="status">
      <h2>已提交，等待管理员审核</h2>
      <p>申请编号：{{ receipt }}。审核通过后更新公开资料。</p>
      <RouterLink :to="detailPath">{{ submission ? '返回首页' : '返回商户详情' }}</RouterLink>
    </section>
    <form v-else novalidate :aria-busy="saving" @submit.prevent="submit">
      <p class="intro">
        {{
          submission
            ? '请搜索并选择门店，补充资料后提交审核。'
            : '已填入当前商户资料，只需修改有误或缺失的内容。'
        }}
      </p>
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
          <label
            >美食品类<select v-model="form.categoryCode" :aria-invalid="!!errors.categoryCode">
              <option value="">请选择分类</option>
              <option v-for="c in visibleCategories" :key="c.code" :value="c.code">
                {{ c.name }}
              </option></select
            ><small>{{ errors.categoryCode }}</small></label
          >
          <label
            >餐厅类型<select v-model="extra.restaurantType">
              <option value="REGULAR">普通餐厅</option>
              <option value="CANTEEN">食堂</option>
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
          ><input v-model="form.locationConfirmed" type="checkbox" />我已核对并确认商户位置</label
        >
        <p v-else class="intro">这家商户还没有位置信息，请搜索并选择门店。</p>
        <small class="error">{{
          errors.latitude || errors.longitude
            ? '请通过高德搜索并选择有效门店。'
            : errors.locationConfirmed
        }}</small>
      </fieldset>
      <!-- 已公开相册独立只读回显，不进入新上传草稿、五张上限、清理队列或提交photoIds。 -->
      <fieldset v-if="!submission" class="existing-merchant-photos">
        <legend>当前商户照片</legend>
        <p class="intro">
          以下为已公开的商户照片，可点击查看大图。已有照片无需重复上传；本次新增照片审核通过后追加到相册。
        </p>
        <RestaurantAlbum :key="id" :restaurant-id="id" :name="sourceName" />
      </fieldset>
      <!-- 新图片使用本地Object URL预览；上传文件在申请通过审核前保持私有。 -->
      <fieldset :disabled="saving">
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
      <p class="notice">审核通过后更新公开资料。</p>
      <p v-if="Object.keys(errors).length" role="alert" class="error">请修改标记的字段后再提交。</p>
      <p v-if="saveError" role="alert" class="error">{{ saveError }}，资料已保留，可重试提交。</p>
      <div class="actions">
        <button
          type="submit"
          :disabled="saving || processingPhotos || uploadingPhotos || failedPhotos"
        >
          {{ saving ? '正在提交…' : '提交审核' }}</button
        ><RouterLink :to="detailPath">取消</RouterLink>
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
