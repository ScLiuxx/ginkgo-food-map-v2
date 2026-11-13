<script setup lang="ts">
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
let categoryController: globalThis.AbortController | undefined
let disposed = false

const ranges = [
  { value: 'QINGSHUIHE', label: '清水河附近', icon: 'campus' },
  { value: 'SHAHE', label: '沙河附近', icon: 'campus' },
  { value: 'USER', label: '我附近', icon: 'locate' },
] as const
const budgets = ['15', '30', '50', '100', 'ANY'].map((value) => ({
  value,
  label: value === 'ANY' ? '不限预算' : `${value}元以内`,
}))
const modes = [
  { value: 'DINE_IN', label: '堂食', icon: 'bowl' },
  { value: 'DELIVERY', label: '外卖', icon: 'bag' },
  { value: 'ANY', label: '不限', icon: undefined },
] as const
const groups = computed(
  () =>
    [
      { key: 'range', label: '在哪里吃', hint: '选择一个出发点', options: ranges },
      {
        key: 'distanceMeters',
        label: '距离范围',
        hint: '按直线距离筛选',
        options: [1000, 3000, 5000].map((value) => ({ value, label: `${value / 1000}公里内` })),
      },
      { key: 'budget', label: '人均预算', hint: '按店铺标注人均筛选', options: budgets },
      {
        key: 'minRating',
        label: '最低评分',
        hint: '五分制综合口碑分',
        options: ['ANY', '3', '3.5', '4'].map((value) => ({
          value,
          label: value === 'ANY' ? '不限评分' : `${value}分及以上`,
        })),
      },
      {
        key: 'category',
        label: '想吃哪一类',
        hint: '单选，也可以不限',
        options: [
          { value: 'ANY', label: '不限类别' },
          ...categories.value
            .filter((c) => c.enabled && c.code !== 'CANTEEN')
            .map((c) => ({ value: c.code, label: c.name })),
        ],
      },
      { key: 'diningMode', label: '怎么吃', hint: '', options: modes },
    ] as const,
)
const originLabel = computed(() =>
  picker.confirmed?.range === 'USER'
    ? '我'
    : picker.confirmed?.range === 'SHAHE'
      ? '沙河'
      : '清水河',
)
const summary = computed(() => {
  const c = picker.confirmed
  if (!c) return []
  return [
    ranges.find((r) => r.value === c.range)?.label,
    `${c.distanceMeters / 1000}公里内`,
    c.budget === 'ANY' ? '不限预算' : `${c.budget}元以内`,
    c.minRating === 'ANY' ? '不限评分' : `${c.minRating}分及以上`,
    c.category === 'ANY'
      ? '不限类别'
      : (categories.value.find((v) => v.code === c.category)?.name ?? '所选类别'),
    modes.find((m) => m.value === c.diningMode)?.label,
  ]
})
const locating = computed(() => waitingLocation.value && (location.preparing || location.locating))
const ready = computed(
  () => picker.complete && (picker.draft.range !== 'USER' || !!userPoint.value) && !picker.loading,
)
const navigation = computed(() => (picker.current ? navigationUrl(picker.current) : null))
const canGo = computed(() => !picker.loading && !picker.unavailable && !picker.error)
const atLast = computed(() => picker.cursor === picker.history.length - 1)
const subtitle = computed(() =>
  picker.phase === 'conditions'
    ? '选好这一顿的条件，剩下的交给随机。'
    : '这一顿，不妨就从这家开始。',
)
const nextLabel = computed(() =>
  !atLast.value ? '下一家' : picker.exhausted ? '重新抽一轮' : '换一家',
)

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
}
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
          <span class="heading-icon"><PickerIcon name="shuffle" :size="22" /></span>
          <div>
            <h2 id="random-title">帮我选一家</h2>
            <p class="subtitle">{{ subtitle }}</p>
          </div>
        </div>
        <button type="button" class="close" aria-label="关闭选店" @click="close">
          <PickerIcon name="close" />
        </button>
      </header>
      <form v-if="picker.phase === 'conditions'" class="picker-form" @submit.prevent="draw">
        <div class="form-body">
          <section v-for="group in groups" :key="group.key" class="field" :data-group="group.key">
            <div class="field-heading">
              <h3 :id="`picker-${group.key}`">{{ group.label }}</h3>
              <small>{{ group.hint }}</small>
            </div>
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
                <PickerIcon
                  v-if="'icon' in option && option.icon"
                  :name="option.icon"
                  :size="15"
                />{{ option.label }}
              </button>
            </div>
            <p
              v-if="group.key === 'category' && categoriesLoading"
              class="inline-note"
              role="status"
            >
              正在加载类别…
            </p>
            <p v-if="group.key === 'category' && categoriesError" class="inline-error" role="alert">
              {{ categoriesError }}
              <button type="button" class="text-button" @click="loadCategories">重试</button>
            </p>
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
          <div class="form-progress">
            <span>{{
              picker.completedCount === groups.length
                ? '条件已选好，可以开始了'
                : `已选 ${picker.completedCount} / ${groups.length} 项条件`
            }}</span
            ><span class="dots" aria-hidden="true"
              ><i
                v-for="group in groups"
                :key="group.key"
                :class="{ done: !!picker.draft[group.key] }"
            /></span>
          </div>
          <button type="submit" class="primary" :disabled="!ready">
            <PickerIcon name="shuffle" />{{ picker.editing ? '按新条件重新选' : '帮我选一家' }}
          </button>
          <button
            v-if="picker.editing"
            type="button"
            class="cancel-edit text-button"
            @click="picker.cancelEdit"
          >
            取消修改，返回本轮
          </button>
          <p v-else class="form-note">仅选普通餐厅 · 排除标注停业 · 每次进入重新选择条件</p>
        </footer>
      </form>
      <div v-else class="picker-results" :aria-busy="picker.loading">
        <div class="result-body">
          <div class="condition-summary">
            <div class="summary-tags" aria-label="本轮条件">
              <span v-for="tag in summary" :key="tag">{{ tag }}</span>
            </div>
            <button type="button" class="text-button" @click="picker.edit">
              修改条件 <PickerIcon name="edit" :size="13" />
            </button>
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
            <div
              class="restaurant-photo"
              :class="{ 'empty-photo': !picker.current.coverPhoto || imageFailed }"
            >
              <img
                v-if="picker.current.coverPhoto && !imageFailed"
                :src="picker.current.coverPhoto.url"
                :alt="`${picker.current.name}的商户相册封面`"
                @error="imageFailed = true"
              />
              <div v-else class="photo-placeholder">
                <PickerIcon name="bowl" :size="48" /><small>{{
                  imageFailed ? '图片暂时无法加载' : '暂无商户照片'
                }}</small>
              </div>
              <span v-if="picker.current.coverPhoto && !imageFailed" class="photo-label"
                >商户相册</span
              >
            </div>
            <p class="match-label">
              <PickerIcon name="check" :size="14" />{{
                picker.unavailable ? '本轮看过的店铺' : '从符合条件的餐厅中随机选出'
              }}
            </p>
            <div class="restaurant-title">
              <h3>{{ picker.current.name }}</h3>
              <span v-if="picker.current.category" class="category-label">{{
                picker.current.category
              }}</span>
            </div>
            <div class="rating">
              <template v-if="picker.current.score !== null"
                ><span class="stars" aria-hidden="true">★</span
                ><strong>{{ formatRating(picker.current.score) }}分</strong></template
              ><span v-else>暂无评分</span><span class="divider">·</span
              ><span>{{ picker.current.reviewCount }}条点评</span>
            </div>
            <div class="facts">
              <div class="fact">
                <PickerIcon name="wallet" :size="23" />
                <div>
                  <small>标注人均</small><strong>{{ priceLabel(picker.current) }}</strong>
                </div>
              </div>
              <div class="fact">
                <PickerIcon name="pin" :size="23" />
                <div>
                  <small>距{{ originLabel }}</small
                  ><strong
                    >{{
                      picker.current.distanceKm === null || picker.current.distanceKm === undefined
                        ? '距离待补'
                        : picker.current.distanceKm.toFixed(1)
                    }}<span
                      v-if="
                        picker.current.distanceKm !== null &&
                        picker.current.distanceKm !== undefined
                      "
                    >
                      公里</span
                    ></strong
                  >
                </div>
              </div>
            </div>
            <p class="address">
              <PickerIcon name="pin" :size="14" />{{ picker.current.address || '详细地址待补充' }}
            </p>
            <div class="actions">
              <RouterLink
                v-if="canGo"
                class="primary"
                :to="`/restaurants/${encodeURIComponent(picker.current.id)}?from=picker`"
                @click.capture="toDetails"
                >查看详情 <PickerIcon name="arrow"
              /></RouterLink>
              <button v-else type="button" class="primary" disabled>
                查看详情 <PickerIcon name="arrow" />
              </button>
              <a
                v-if="navigation && canGo"
                class="secondary"
                :href="navigation"
                target="_blank"
                rel="noopener noreferrer"
                ><PickerIcon name="navigation" />去导航</a
              >
              <button v-else type="button" class="secondary" disabled>
                <PickerIcon name="navigation" />去导航
              </button>
            </div>
          </template>
          <section v-else-if="!picker.loading && !picker.error" class="empty-body">
            <span class="empty-icon"><PickerIcon name="search" :size="32" /></span>
            <h3>暂时没有符合条件的店</h3>
            <p>试试调整距离、预算、评分或类别，<br />我们会按你确认的条件重新选择。</p>
            <button type="button" class="primary" @click="picker.edit">修改条件</button>
          </section>
        </div>
        <footer v-if="picker.current" class="result-footer">
          <div class="history-row">
            <button
              type="button"
              class="history-button previous"
              :disabled="picker.loading || picker.cursor <= 0"
              @click="picker.visit(picker.cursor - 1)"
            >
              <PickerIcon name="left" :size="14" />上一家</button
            ><span class="history-label"
              >已看 <strong>{{ picker.history.length }}</strong> 家 · 本轮不重复</span
            ><button
              type="button"
              class="history-button next"
              :disabled="picker.loading"
              @click="picker.exhausted && atLast ? picker.restart() : picker.next()"
            >
              <PickerIcon name="shuffle" :size="14" />{{ nextLabel }}
            </button>
          </div>
          <p class="result-note">人均为参考值，距离为直线距离。</p>
        </footer>
      </div>
    </div>
  </dialog>
</template>

<style scoped src="./random-restaurant.css"></style>
