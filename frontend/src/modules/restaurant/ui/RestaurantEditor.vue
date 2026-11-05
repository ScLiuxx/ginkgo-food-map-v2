<script setup lang="ts">
import axios from 'axios'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRouter } from 'vue-router'
import { authToken, getAccessToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import {
  createRestaurant,
  fetchRestaurantCategories,
  fetchRestaurantManagement,
  updateRestaurant,
} from '../api/restaurantApi'
import type { RestaurantCategory } from '../api/restaurantApi'
import {
  characterCount,
  draftTags,
  restaurantDraft,
  restaurantInput,
  validateRestaurantDraft,
} from '../model/restaurantForm'
import type { RestaurantErrors, RestaurantField } from '../model/restaurantForm'
import { useRestaurantCatalog } from '../model/catalog.store'

const props = defineProps<{ id?: string; admin?: boolean }>()
const router = useRouter()
const catalog = useRestaurantCatalog()
const form = reactive(restaurantDraft())
const categories = ref<RestaurantCategory[]>([])
const originalCategory = ref('')
// 直接维护协议保留现有类型；编辑食堂时只隐藏不适用的分类，不擅自切换类型。
const restaurantType = ref<'REGULAR' | 'CANTEEN'>('REGULAR')
const loading = ref(false)
const loadError = ref('')
const saving = ref(false)
const saveError = ref('')
const errors = ref<RestaurantErrors>({})
const sessionChanged = ref(false)
const boundToken = getAccessToken()
const saved = ref(false)
const baseline = ref(JSON.stringify(form))
const dirty = computed(() => JSON.stringify(form) !== baseline.value)
const editor = ref<globalThis.HTMLFormElement>()
const path = computed(() =>
  props.admin
    ? props.id
      ? `/admin/restaurants/${encodeURIComponent(props.id)}/edit`
      : '/admin/restaurants/new'
    : props.id
      ? `/restaurants/${encodeURIComponent(props.id)}/edit`
      : '/restaurants/new',
)
// 管理员保存和取消都回到管理端，避免被前台身份守卫重定向到审核页。
const returnPath = computed(() =>
  props.admin
    ? '/admin/restaurants'
    : props.id
      ? `/restaurants/${encodeURIComponent(props.id)}`
      : '/',
)
const loginTarget = computed(() => ({
  path: '/login',
  query: { redirect: path.value },
}))
const visibleCategories = computed(() =>
  categories.value.filter((c) => c.enabled || c.code === originalCategory.value),
)
const tagCount = computed(() =>
  draftTags(form.tags).reduce((sum, tag) => sum + characterCount(tag), 0),
)
let controller: globalThis.AbortController | undefined
let generation = 0
let disposed = false

// 快捷档位只辅助填写估值；用户仍能调整具体整数金额，不能误标为平台核实消费。
const shortcuts = [
  ['15内', 15],
  ['15–30', 25],
  ['30–50', 40],
  ['50–80', 65],
  ['80–120', 100],
  ['120–200', 160],
  ['200–500', 350],
  ['500–1000', 750],
  ['1000+', 1200],
] as const

/** 快捷选择始终明确为估值，实际金额仍可在输入框内修改。 */
function setEstimate(value: number): void {
  form.priceKind = 'ESTIMATE'
  form.price = String(value)
}

/** 分类与授权快照均从服务端读取，缺失/删除/无权限时不能退回空白编辑表单。 */
async function load(): Promise<void> {
  if (!boundToken || sessionChanged.value) return
  const current = ++generation
  controller?.abort()
  controller = new globalThis.AbortController()
  loading.value = true
  loadError.value = ''
  try {
    const [dictionary, snapshot] = await Promise.all([
      fetchRestaurantCategories(controller.signal),
      props.id
        ? fetchRestaurantManagement(props.id, boundToken, controller.signal)
        : Promise.resolve(null),
    ])
    if (current !== generation) return
    if (snapshot?.deleted) {
      loadError.value = '这家餐厅已删除，不能编辑。'
      return
    }
    categories.value = dictionary
    originalCategory.value = snapshot?.restaurant.categoryCode ?? ''
    restaurantType.value = snapshot?.restaurant.restaurantType ?? 'REGULAR'
    Object.assign(form, restaurantDraft(snapshot?.restaurant))
    baseline.value = JSON.stringify(form)
  } catch (reason) {
    if (current !== generation || controller.signal.aborted) return
    const status = axios.isAxiosError(reason) ? reason.response?.status : undefined
    loadError.value =
      status === 403
        ? '你没有编辑这家餐厅的权限。'
        : status === 404
          ? '餐厅不存在或已被删除。'
          : toErrorMessage(reason)
  } finally {
    if (current === generation) loading.value = false
  }
}
onMounted(() => {
  void load()
})

// 凭证改变后锁住原草稿，不把A账号填写的内容交给B账号；重新进入页面才能继续。
watch(authToken, (token) => {
  if (token === boundToken) return
  sessionChanged.value = true
  generation++
  controller?.abort()
})
watch(
  () => [form.latitude, form.longitude],
  () => {
    form.locationConfirmed = false
  },
  { flush: 'sync' },
)

// 修改输入后移除该字段的旧提示，避免已填正确的坐标仍显示上一次提交的错误。
watch(
  () => ({ ...form }),
  (current, previous) => {
    for (const field of Object.keys(current) as RestaurantField[]) {
      if (current[field] !== previous[field]) delete errors.value[field]
    }
    if (current.priceKind !== previous.priceKind) delete errors.value.price
  },
)

/** 汇总服务端字段错误并定位输入；业务错误保留全部输入供核对或手动重试。 */
function serverErrors(reason: unknown): void {
  if (!axios.isAxiosError(reason)) return
  const fields = reason.response?.data?.data
  if (!Array.isArray(fields)) return
  const mapping: Record<string, RestaurantField> = { pricePerPerson: 'price' }
  for (const entry of fields) {
    if (typeof entry?.field !== 'string' || typeof entry.message !== 'string') continue
    const field = entry.field.split('[')[0]
    const target = mapping[field] ?? field
    if (Object.hasOwn(form, target)) errors.value[target as RestaurantField] = entry.message
  }
}

/** 创建/完整替换仅提交一次，成功才跳转；超时不自动重发，避免已提交却生成重复餐厅。 */
async function submit(): Promise<void> {
  if (
    saving.value ||
    loading.value ||
    loadError.value ||
    sessionChanged.value ||
    !boundToken ||
    getAccessToken() !== boundToken
  )
    return
  errors.value = validateRestaurantDraft(form, restaurantType.value)
  const category = categories.value.find((c) => c.code === form.categoryCode)
  if (
    restaurantType.value === 'REGULAR' &&
    (!category || (!category.enabled && category.code !== originalCategory.value))
  )
    errors.value.categoryCode = '请选择可用分类。'
  saveError.value = ''
  if (Object.keys(errors.value).length) {
    await focusError()
    return
  }
  saving.value = true
  try {
    const input = restaurantInput(form, restaurantType.value)
    const result = props.id
      ? await updateRestaurant(props.id, input, boundToken)
      : await createRestaurant(input, boundToken)
    if (disposed || getAccessToken() !== boundToken || sessionChanged.value) return
    saved.value = true
    catalog.invalidate()
    await router.replace(
      props.admin
        ? { path: '/admin/restaurants', query: { saved: result.restaurant.id } }
        : `/restaurants/${encodeURIComponent(result.restaurant.id)}`,
    )
  } catch (reason) {
    if (disposed || getAccessToken() !== boundToken) return
    serverErrors(reason)
    const status = axios.isAxiosError(reason) ? reason.response?.status : undefined
    saveError.value =
      status === 403
        ? '当前账号没有保存权限，请重新核对登录账号。'
        : status === 404
          ? '餐厅不存在或已被删除，未能保存。'
          : status === 409
            ? '餐厅当前状态不允许保存，请重新打开详情核对。'
            : axios.isAxiosError(reason) && !reason.response
              ? '尚未确认保存结果。请先检查餐厅目录或详情，再决定是否重试，避免重复新增。'
              : toErrorMessage(reason)
    await focusError()
  } finally {
    saving.value = false
  }
}

async function focusError(): Promise<void> {
  await nextTick()
  editor.value?.querySelector<globalThis.HTMLElement>('[aria-invalid="true"]')?.focus()
}

/** 离开只保护当前页内草稿，不把草稿或账号凭据额外写入持久存储。 */
function beforeUnload(event: globalThis.BeforeUnloadEvent): void {
  if ((!dirty.value && !saving.value) || saved.value) return
  event.preventDefault()
  event.returnValue = ''
}
onMounted(() => globalThis.addEventListener('beforeunload', beforeUnload))
/** 同一路由切换餐厅ID也会销毁草稿，需与离开编辑页使用相同保护。 */
function canLeave(): boolean {
  return (
    saved.value ||
    (!saving.value && (!dirty.value || globalThis.confirm('尚有未保存的餐厅资料，确定离开吗？')))
  )
}
onBeforeRouteLeave(canLeave)
onBeforeRouteUpdate((to, from) => to.params.id === from.params.id || canLeave())
onBeforeUnmount(() => {
  disposed = true
  generation++
  controller?.abort()
  globalThis.removeEventListener('beforeunload', beforeUnload)
})
</script>

<template>
  <section class="restaurant-editor" aria-labelledby="editor-title">
    <RouterLink :to="returnPath"
      >← {{ admin ? '返回商户管理' : id ? '返回餐厅详情' : '返回餐厅目录' }}</RouterLink
    >
    <p class="eyebrow">{{ admin ? '商户管理 · 直接维护' : 'SHARE A PLACE' }}</p>
    <h1 id="editor-title">{{ id ? '编辑餐厅' : '新增餐厅' }}</h1>
    <section v-if="!boundToken || sessionChanged" class="editor-notice" role="alert">
      <p>
        {{
          sessionChanged
            ? '登录状态已变化。为避免用其他账号提交原草稿，请重新打开此页。'
            : '登录后才能新增或维护餐厅。'
        }}
      </p>
      <RouterLink v-if="!authToken" :to="loginTarget">前往登录</RouterLink>
      <a v-else :href="path">重新打开此页</a>
    </section>
    <p v-else-if="loading" role="status">正在读取餐厅资料与分类…</p>
    <section v-else-if="loadError" class="editor-notice" role="alert">
      <p>{{ loadError }}</p>
      <button type="button" @click="load">重新加载</button>
    </section>
    <form v-else ref="editor" novalidate :aria-busy="saving" @submit.prevent="submit">
      <p class="editor-intro">
        {{
          id
            ? '保存会替换整份资料及标签，请核对所有内容。'
            : '记录你发现的店铺。请填写真实信息，并核对餐厅位置。'
        }}
      </p>
      <p v-if="admin" class="field-hint">
        保存后直接公开生效，无需审核。当前可维护名称、分类、人均、地址、坐标、经营状态和标签；已有店铺的餐厅类型及堂食／外卖资料保持原值，新建为普通餐厅。
      </p>
      <fieldset :disabled="saving">
        <legend>基本资料</legend>
        <div class="editor-grid">
          <div class="editor-field">
            <label for="restaurant-name"
              >餐厅名称 <span>必填 · {{ characterCount(form.name.trim()) }}/40</span></label
            >
            <input
              id="restaurant-name"
              v-model="form.name"
              :aria-invalid="!!errors.name"
              aria-describedby="error-name"
              autocomplete="off"
            />
            <p v-if="errors.name" id="error-name" class="field-error">
              {{ errors.name }}
            </p>
          </div>
          <div v-if="restaurantType === 'REGULAR'" class="editor-field">
            <label for="restaurant-category">分类 <span>必填</span></label>
            <select
              id="restaurant-category"
              v-model="form.categoryCode"
              :aria-invalid="!!errors.categoryCode"
              aria-describedby="error-category"
            >
              <option value="">请选择分类</option>
              <option
                v-for="category in visibleCategories"
                :key="category.code"
                :value="category.code"
              >
                {{ category.name }}{{ category.enabled ? '' : '（已停用，可保留）' }}
              </option>
            </select>
            <p v-if="errors.categoryCode" id="error-category" class="field-error">
              {{ errors.categoryCode }}
            </p>
          </div>
          <div class="editor-field">
            <label for="restaurant-price-kind">人均类型</label>
            <select id="restaurant-price-kind" v-model="form.priceKind">
              <option value="UNKNOWN">未知</option>
              <option value="EXACT">填报具体金额</option>
              <option value="ESTIMATE">估值</option>
            </select>
          </div>
          <div class="editor-field">
            <label for="restaurant-price">人均消费（元）</label>
            <input
              id="restaurant-price"
              v-model="form.price"
              inputmode="numeric"
              :disabled="form.priceKind === 'UNKNOWN'"
              :aria-invalid="!!errors.price"
              aria-describedby="error-price"
              placeholder="1–5000，整数元"
            />
            <p v-if="errors.price" id="error-price" class="field-error">
              {{ errors.price }}
            </p>
          </div>
        </div>
        <div class="price-shortcuts" aria-label="快捷填写人均估值">
          <button
            v-for="[label, value] in shortcuts"
            :key="label"
            type="button"
            @click="setEstimate(value)"
          >
            {{ label }}
          </button>
        </div>
        <p class="field-hint">快捷档位填写估值，可继续调整；填报金额不表示平台已核实消费。</p>
        <div class="editor-field">
          <label for="restaurant-address"
            >地址 <span>可留空 · {{ characterCount(form.address.trim()) }}/80</span></label
          >
          <input
            id="restaurant-address"
            v-model="form.address"
            :aria-invalid="!!errors.address"
            aria-describedby="error-address"
            autocomplete="off"
          />
          <p v-if="errors.address" id="error-address" class="field-error">
            {{ errors.address }}
          </p>
        </div>
        <div class="editor-field">
          <label for="restaurant-status">经营状态</label>
          <select id="restaurant-status" v-model="form.businessStatus">
            <option value="OPERATING">正常经营记录</option>
            <option value="CLOSED">标注停业</option>
          </select>
          <p class="field-hint">
            经营记录不代表此刻正在营业；停业后仍可阅读，删除则从公开目录隐藏。
          </p>
        </div>
      </fieldset>
      <fieldset :disabled="saving">
        <legend>确认餐厅位置</legend>
        <p id="coordinate-hint" class="field-hint">
          当前支持手动填写 GCJ-02 坐标，地图选点尚未接入。请从可信来源核对餐厅位置，不要直接填写
          GPS/WGS84 坐标；不会自动填入校园中心。
        </p>
        <div class="editor-grid">
          <div class="editor-field">
            <label for="restaurant-latitude">纬度 <span>必填</span></label>
            <input
              id="restaurant-latitude"
              v-model="form.latitude"
              inputmode="decimal"
              :aria-invalid="!!errors.latitude"
              aria-describedby="coordinate-hint error-latitude"
              placeholder="3–54，最多6位小数"
            />
            <p v-if="errors.latitude" id="error-latitude" class="field-error">
              {{ errors.latitude }}
            </p>
          </div>
          <div class="editor-field">
            <label for="restaurant-longitude">经度 <span>必填</span></label>
            <input
              id="restaurant-longitude"
              v-model="form.longitude"
              inputmode="decimal"
              :aria-invalid="!!errors.longitude"
              aria-describedby="coordinate-hint error-longitude"
              placeholder="73–136，最多6位小数"
            />
            <p v-if="errors.longitude" id="error-longitude" class="field-error">
              {{ errors.longitude }}
            </p>
          </div>
        </div>
        <label class="location-confirm" for="restaurant-location-confirmed"
          ><input
            id="restaurant-location-confirmed"
            v-model="form.locationConfirmed"
            type="checkbox"
            :aria-invalid="!!errors.locationConfirmed"
            aria-describedby="error-location"
          />我已核对上述 GCJ-02 坐标，确认它对应这家餐厅的位置。</label
        >
        <p v-if="errors.locationConfirmed" id="error-location" class="field-error">
          {{ errors.locationConfirmed }}
        </p>
      </fieldset>
      <fieldset :disabled="saving">
        <legend>餐厅标签</legend>
        <div class="editor-field">
          <label for="restaurant-tags"
            >每行一个标签
            <span>{{ draftTags(form.tags).length }}/8 个 · {{ tagCount }}/80 字</span></label
          >
          <textarea
            id="restaurant-tags"
            v-model="form.tags"
            rows="4"
            :aria-invalid="!!errors.tags"
            aria-describedby="tags-hint error-tags"
            placeholder="例如：学生聚餐&#10;晚餐"
          />
          <p id="tags-hint" class="field-hint">
            空行忽略，重复标签由服务端合并并保留首项顺序。清空后保存会移除原标签。
          </p>
          <p v-if="errors.tags" id="error-tags" class="field-error">
            {{ errors.tags }}
          </p>
        </div>
      </fieldset>
      <div v-if="saveError || Object.keys(errors).length" class="editor-notice" role="alert">
        <p>{{ saveError || '请修改上方标记的内容后再保存。' }}</p>
        <RouterLink v-if="saveError" :to="admin ? '/admin/restaurants' : '/'"
          >到目录核对保存结果</RouterLink
        >
      </div>
      <div class="editor-actions">
        <button type="submit" class="primary-action" :disabled="saving">
          {{ saving ? '正在保存…' : id ? '保存修改' : '创建餐厅' }}
        </button>
        <RouterLink :to="returnPath" :aria-disabled="saving">取消</RouterLink>
      </div>
    </form>
  </section>
</template>

<style scoped>
.restaurant-editor {
  width: min(840px, calc(100% - 32px));
  margin: 32px auto 80px;
  color: #514b3c;
}
.restaurant-editor > a {
  color: #786746;
  font-size: 14px;
}
.eyebrow {
  margin-top: 32px;
}
h1 {
  font-family: Georgia, 'Songti SC', serif;
  font-size: clamp(30px, 5vw, 44px);
  margin: 12px 0 24px;
}
form {
  padding: clamp(18px, 4vw, 36px);
  background: #fffdf8;
  border: 1px solid #e2d9c9;
  border-radius: 22px;
}
.editor-intro,
.field-hint {
  color: #786c58;
  font-size: 13px;
  line-height: 1.8;
}
fieldset {
  min-width: 0;
  border: 0;
  border-top: 1px solid #e5ddcd;
  padding: 20px 0;
  margin: 24px 0 0;
}
legend {
  font-weight: 700;
  padding-right: 16px;
}
.editor-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 24px;
}
.editor-field {
  min-width: 0;
  margin: 12px 0;
}
.editor-field label {
  display: block;
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 9px;
}
label span {
  font-size: 12px;
  font-weight: 400;
  color: #82725c;
}
input:not([type='checkbox']),
select,
textarea {
  width: 100%;
  min-width: 0;
  min-height: 46px;
  padding: 10px 12px;
  border: 1px solid #d8d0c2;
  border-radius: 10px;
  background: #fff;
  color: #403c32;
  font: inherit;
}
textarea {
  resize: vertical;
}
input:focus-visible,
select:focus-visible,
textarea:focus-visible,
button:focus-visible,
a:focus-visible {
  outline: 2px solid #a77829;
  outline-offset: 3px;
}
[aria-invalid='true'] {
  border-color: #a44c3d !important;
}
.location-confirm {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 0;
  font-size: 14px;
  line-height: 1.8;
  cursor: pointer;
}
.location-confirm input {
  margin-top: 6px;
  flex-shrink: 0;
  width: 18px;
  height: 18px;
}
.field-error {
  color: #963f32;
  font-size: 13px;
  line-height: 1.6;
}
.price-shortcuts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.price-shortcuts button,
.editor-notice button {
  min-height: 44px;
  padding: 8px 12px;
  border: 1px solid #ded3bc;
  border-radius: 8px;
  background: #f5f0e6;
  color: #705a34;
  font: inherit;
  cursor: pointer;
}
.editor-notice {
  margin: 20px 0;
  padding: 16px;
  border-radius: 12px;
  background: #f8eadf;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.editor-notice a {
  color: #775623;
}
.editor-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
  align-items: center;
  margin-top: 20px;
}
.editor-actions a {
  padding: 12px;
  color: #786746;
}
button:disabled,
fieldset:disabled {
  opacity: 0.65;
}
@media (max-width: 560px) {
  .editor-grid {
    grid-template-columns: 1fr;
    gap: 0;
  }
  .restaurant-editor {
    width: calc(100% - 28px);
  }
}
</style>
