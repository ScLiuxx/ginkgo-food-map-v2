<script setup lang="ts">
import ResourceLink from '@/shared/ui/ResourceLink.vue'
import AppLink from '@/shared/ui/AppLink.vue'
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useUserLocation } from '@/modules/map/model/userLocation.store'
import { navigationUrl } from '@/modules/map/model/map'
import { formatRating } from '@/shared/lib/formatRating'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { fetchRestaurantCategories, type RestaurantCategory } from '../api/restaurantApi'
import { priceLabel } from '../model/catalog.logic'
import { useRestaurantPicker, type PickerDraft } from '../model/picker.store'
import PickerIcon from './PickerIcon.vue'

const picker = useRestaurantPicker()
const location = useUserLocation()
const route = useRoute()
const dialog = ref<globalThis.HTMLDialogElement>()
const categories = ref<RestaurantCategory[]>([])
const categoriesError = ref('')
const categoriesLoading = ref(false)
const userPoint = ref<[number, number] | null>(null)
const waitingLocation = ref(false)
const imageFailed = ref(false)
// 展开状态只属于当前表单，不写入选店会话；收起不能清空用户已选的条件。
const expandedGroup = ref<keyof PickerDraft | null>(null)
let categoryController: globalThis.AbortController | undefined
let disposed = false

const ranges = [
  { value: 'QINGSHUIHE', label: '清水河附近' },
  { value: 'SHAHE', label: '沙河附近' },
  { value: 'USER', label: '我附近' },
] as const
const budgets = ['15', '30', '50', '100', 'ANY'].map((value) => ({
  value,
  label: value === 'ANY' ? '不限预算' : `${value}元以内`,
}))
const modes = [
  { value: 'DINE_IN', label: '堂食' },
  { value: 'DELIVERY', label: '外卖' },
  { value: 'ANY', label: '不限' },
] as const
const groups = computed(
  () =>
    [
      { key: 'range', label: '出发位置', options: ranges },
      {
        key: 'distanceMeters',
        label: '直线距离',
        options: [1000, 3000, 5000].map((value) => ({ value, label: `${value / 1000}公里内` })),
      },
      { key: 'budget', label: '人均预算', options: budgets },
      {
        key: 'minRating',
        label: '最低评分',
        options: ['ANY', '3', '3.5', '4'].map((value) => ({
          value,
          label: value === 'ANY' ? '不限评分' : `${value}分及以上`,
        })),
      },
      {
        key: 'category',
        label: '餐厅分类',
        options: [
          { value: 'ANY', label: '不限类别' },
          ...categories.value
            .filter((c) => c.enabled && c.code !== 'CANTEEN')
            .map((c) => ({ value: c.code, label: c.name })),
        ],
      },
      { key: 'diningMode', label: '用餐方式', options: modes },
    ] as const,
)
const originLabel = computed(() =>
  picker.confirmed?.range === 'USER'
    ? '你'
    : picker.confirmed?.range === 'SHAHE'
      ? '沙河'
      : '清水河',
)
const summary = computed(() => {
  const c = picker.confirmed
  if (!c) return []
  return [ranges.find((r) => r.value === c.range)?.label, `${c.distanceMeters / 1000}公里内`]
})
const locating = computed(() => waitingLocation.value && (location.preparing || location.locating))
const ready = computed(
  () => picker.complete && (picker.draft.range !== 'USER' || !!userPoint.value) && !picker.loading,
)
const navigation = computed(() => (picker.current ? navigationUrl(picker.current) : null))
const canGo = computed(() => !picker.loading && !picker.unavailable && !picker.error)
const atLast = computed(() => picker.cursor === picker.history.length - 1)
// 校区距离不能标成“距你”；未知距离保持缺失提示，不用0公里替代。
const distanceLabel = computed(() => {
  const distance = picker.current?.distanceKm
  return distance === null || distance === undefined || !Number.isFinite(distance) || distance < 0
    ? '距离待补'
    : `距${originLabel.value} ${distance.toFixed(1)} 公里`
})
const nextLabel = computed(() =>
  !atLast.value ? '下一家' : picker.exhausted ? '重新抽一轮' : '换一家',
)
const submitLabel = computed(() => {
  if (!picker.complete) return `还需选择 ${groups.value.length - picker.completedCount} 项`
  if (locating.value) return '正在定位…'
  if (picker.draft.range === 'USER' && !userPoint.value) return '请先完成定位'
  return picker.editing ? '按新条件重新选' : '帮我选一家'
})

/** 摘要按选项值匹配，显式“不限”与尚未选择保持区别；字典故障不伪造分类名称。 */
function selectedLabel(key: keyof PickerDraft) {
  const value = picker.draft[key]
  if (!value) return '请选择'
  return (
    groups.value.find((group) => group.key === key)?.options.find((o) => o.value === value)
      ?.label ?? '所选类别'
  )
}

function toggleGroup(key: keyof PickerDraft) {
  expandedGroup.value = expandedGroup.value === key ? null : key
}

/** 分类独立读取，仅缓存字典而不缓存用户选择；失败单独重试，不影响返回后的结果回看。 */
async function loadCategories() {
  if (categoriesLoading.value || categories.value.length) return
  categoryController = new globalThis.AbortController()
  categoriesLoading.value = true
  categoriesError.value = ''
  try {
    const data = await fetchRestaurantCategories(categoryController.signal)
    if (!disposed) categories.value = data
  } catch (reason) {
    if (!disposed && !categoryController.signal.aborted)
      categoriesError.value = toErrorMessage(reason)
  } finally {
    if (!disposed) categoriesLoading.value = false
  }
}
async function locateMe() {
  userPoint.value = null
  waitingLocation.value = true
  await location.locate()
}
function choose(key: keyof PickerDraft, value: string | number) {
  Object.assign(picker.draft, { [key]: value })
  picker.error = ''
  if (key === 'range') {
    waitingLocation.value = false
    userPoint.value = null
    if (value === 'USER') void locateMe()
  }
  expandedGroup.value = null
  // 选项隐藏后把焦点还给该行；键盘用户可以继续Tab选择下一项，不掉回页面底层。
  void nextTick(() => {
    if (dialog.value?.open && picker.phase === 'conditions' && expandedGroup.value === null)
      dialog.value.querySelector<globalThis.HTMLButtonElement>(`#picker-${key}`)?.focus()
  })
}
watch(
  () => picker.phase,
  () => {
    expandedGroup.value = null
  },
)
watch(
  () => location.location,
  (point) => {
    if (waitingLocation.value && picker.draft.range === 'USER' && point && dialog.value?.open) {
      userPoint.value = [...point.center]
      waitingLocation.value = false
    }
  },
)
watch(
  () => [picker.current?.id, picker.current?.coverPhoto?.url],
  () => {
    imageFailed.value = false
  },
)

async function open(resume = false) {
  expandedGroup.value = null
  if (!resume) {
    picker.reset()
    userPoint.value = null
    waitingLocation.value = false
  } else if (picker.confirmed?.range === 'USER') {
    userPoint.value = [picker.confirmed.longitude!, picker.confirmed.latitude!]
  }
  dialog.value?.showModal()
  void loadCategories()
  picker.pendingReturn = false
  if (resume && picker.current) await picker.visit(picker.cursor)
}
function close() {
  picker.reset()
  waitingLocation.value = false
  userPoint.value = null
  dialog.value?.close()
}
async function draw() {
  if (!ready.value) return
  await picker.start(userPoint.value ?? undefined)
  await nextTick()
  if (dialog.value) dialog.value.scrollTop = 0
}
/** 普通点击暂挂本轮；修饰键/新标签页不关闭原弹窗，返回目标只允许站内固定首页。 */
function toDetails(event: globalThis.MouseEvent) {
  if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
  picker.pendingReturn = true
  picker.originRoute = route.fullPath
  picker.cancel()
  dialog.value?.close()
}
onMounted(() => {
  if (picker.pendingReturn || route.query.picker === 'return') void open(picker.confirmed !== null)
})
onUnmounted(() => {
  disposed = true
  categoryController?.abort()
  waitingLocation.value = false
  picker.cancel()
})
</script>

<template>
  <span class="random-banner"
    ><button type="button" @click="open(false)">
      <PickerIcon name="shuffle" :size="21" />帮我选一家
    </button></span
  >
  <dialog ref="dialog" class="random-dialog" aria-labelledby="random-title" @cancel.prevent="close">
    <div class="picker-shell">
      <header class="dialog-header">
        <div class="heading">
          <h2 id="random-title">帮我选一家</h2>
        </div>
        <button type="button" class="close" aria-label="关闭选店" @click="close">
          <PickerIcon name="close" />
        </button>
      </header>
      <form v-if="picker.phase === 'conditions'" class="picker-form" @submit.prevent="draw">
        <div class="form-body">
          <section v-for="group in groups" :key="group.key" class="field" :data-group="group.key">
            <button
              :id="`picker-${group.key}`"
              type="button"
              class="field-toggle"
              :aria-expanded="expandedGroup === group.key"
              :aria-controls="`picker-options-${group.key}`"
              @click="toggleGroup(group.key)"
            >
              <span class="field-label">{{ group.label }}</span>
              <span class="field-value" :class="{ placeholder: !picker.draft[group.key] }">{{
                selectedLabel(group.key)
              }}</span>
              <PickerIcon
                name="left"
                class="field-arrow"
                :class="{ expanded: expandedGroup === group.key }"
                :size="15"
              />
            </button>
            <div
              v-show="expandedGroup === group.key"
              :id="`picker-options-${group.key}`"
              class="field-options"
            >
              <div class="chips" role="group" :aria-labelledby="`picker-${group.key}`">
                <button
                  v-for="option in group.options"
                  :key="option.value"
                  type="button"
                  class="chip"
                  :class="{ selected: picker.draft[group.key] === option.value }"
                  :aria-pressed="picker.draft[group.key] === option.value"
                  :data-value="option.value"
                  @click="choose(group.key, option.value)"
                >
                  {{ option.label }}
                </button>
              </div>
              <p
                v-if="group.key === 'category' && categoriesLoading"
                class="inline-note"
                role="status"
              >
                正在加载类别…
              </p>
              <p
                v-if="group.key === 'category' && categoriesError"
                class="inline-error"
                role="alert"
              >
                {{ categoriesError }}
                <button type="button" class="text-button" @click="loadCategories">重试</button>
              </p>
            </div>
            <div
              v-if="group.key === 'range' && picker.draft.range === 'USER'"
              class="location-feedback"
              role="status"
            >
              <span v-if="locating">正在获取位置，请在浏览器提示中允许定位…</span>
              <span v-else-if="userPoint">已取得位置，仅用于本轮选店。</span>
              <span v-else>定位尚未成功，可重试或改选校区。</span>
              <button v-if="!locating" type="button" class="text-button" @click="locateMe">
                {{ userPoint ? '更新位置' : '重试定位' }}
              </button>
            </div>
          </section>
        </div>
        <footer class="form-footer">
          <p v-if="picker.error" class="inline-error" role="alert">{{ picker.error }}</p>
          <button type="submit" class="primary" :disabled="!ready">
            {{ submitLabel }}
          </button>
          <button
            v-if="picker.editing"
            type="button"
            class="cancel-edit text-button"
            @click="picker.cancelEdit"
          >
            取消修改，返回本轮
          </button>
        </footer>
      </form>
      <div v-else class="picker-results" :aria-busy="picker.loading">
        <div class="result-body">
          <div class="condition-summary">
            <div class="summary-tags" aria-label="本轮位置与直线距离">
              <span v-for="tag in summary" :key="tag">{{ tag }}</span>
            </div>
            <button type="button" class="text-button" @click="picker.edit">修改条件</button>
          </div>
          <p v-if="picker.loading" class="inline-note" role="status">
            {{ picker.current ? '正在核对店铺资料…' : '正在按条件选择餐厅…' }}
          </p>
          <div v-if="picker.error" class="notice error-notice" role="alert">
            {{ picker.error }}
            <button
              type="button"
              class="text-button"
              :disabled="picker.loading"
              @click="picker.retry"
            >
              重试
            </button>
          </div>
          <p v-if="picker.unavailable" class="notice" role="status">
            这家店已不符合本轮条件或暂不可见，请换一家。
          </p>
          <p v-else-if="picker.current && picker.matchedCount === 1" class="notice" role="status">
            只有这一家符合条件。
          </p>
          <p v-else-if="picker.current && picker.exhausted && atLast" class="notice" role="status">
            本轮已经看完，可以回看或重新抽一轮。
          </p>
          <template v-if="picker.current">
            <!-- 缺图或图片失败时直接收起，不用占位图和装饰文案挤占店铺资料。 -->
            <div v-if="picker.current.coverPhoto && !imageFailed" class="restaurant-photo">
              <img
                :src="picker.current.coverPhoto.url"
                :alt="`${picker.current.name}的商户相册封面`"
                @error="imageFailed = true"
              />
            </div>
            <div class="restaurant-title">
              <h3>{{ picker.current.name }}</h3>
              <span v-if="picker.current.category" class="category-label">{{
                picker.current.category
              }}</span>
            </div>
            <div class="rating-row">
              <div class="rating">
                <template v-if="picker.current.score !== null"
                  ><span class="stars" aria-hidden="true">★</span
                  ><strong>{{ formatRating(picker.current.score) }}分</strong></template
                ><span v-else>暂无评分</span><span class="divider">·</span
                ><span>{{ picker.current.reviewCount }}条点评</span>
              </div>
              <span class="distance">{{ distanceLabel }}</span>
            </div>
            <p class="price">
              {{ priceLabel(picker.current)
              }}<span v-if="picker.current.price !== null && picker.current.price > 0"> / 人</span>
            </p>
            <p class="address">
              {{ picker.current.address || '详细地址待补充' }}
            </p>
          </template>
          <section v-else-if="!picker.loading && !picker.error" class="empty-body">
            <span class="empty-icon"><PickerIcon name="search" :size="32" /></span>
            <h3>暂时没有符合条件的店</h3>
            <p>试试调整距离、预算、评分或类别，<br />我们会按你确认的条件重新选择。</p>
            <button type="button" class="primary" @click="picker.edit">修改条件</button>
          </section>
        </div>
        <footer v-if="picker.current" class="result-footer">
          <!-- 主操作始终可见；回看时先前进历史，末尾耗尽才重新开启一轮。 -->
          <button
            type="button"
            class="primary next"
            :disabled="picker.loading"
            @click="picker.exhausted && atLast ? picker.restart() : picker.next()"
          >
            {{ nextLabel }}
          </button>
          <div class="actions">
            <AppLink
              v-if="canGo"
              class="detail-link"
              :to="`/restaurants/${encodeURIComponent(picker.current.id)}?from=picker`"
              @click.capture="toDetails"
              >查看详情</AppLink
            >
            <button v-else type="button" class="detail-link" disabled>查看详情</button>
            <ResourceLink
              v-if="navigation && canGo"
              class="navigation-link"
              :href="navigation"
              target="_blank"
              rel="noopener noreferrer"
              >导航</ResourceLink
            >
            <button v-else type="button" class="navigation-link" disabled>导航</button>
          </div>
          <div class="history-row">
            <button
              type="button"
              class="history-button previous"
              :disabled="picker.loading || picker.cursor <= 0"
              @click="picker.visit(picker.cursor - 1)"
            >
              <PickerIcon name="left" :size="14" />上一家</button
            ><span class="history-label"
              >已看 <strong>{{ picker.history.length }}</strong> 家</span
            >
          </div>
        </footer>
      </div>
    </div>
  </dialog>
</template>

<style scoped src="./random-restaurant.css"></style>
