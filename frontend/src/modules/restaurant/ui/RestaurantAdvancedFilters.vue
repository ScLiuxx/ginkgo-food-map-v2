<script setup lang="ts">
import { computed, nextTick, onUnmounted, reactive, ref, useId } from 'vue'
import type { CatalogFilters } from '../model/restaurant.types'
import { useRestaurantCatalog } from '../model/catalog.store'

/** 高级条件独立草稿，仅确认时批量写回；不触碰分类、排序、关键词与经营状态。 */
const props = withDefaults(defineProps<{ filters?: CatalogFilters; campusFilters?: boolean }>(), {
  filters: undefined,
  campusFilters: false,
})
const catalog = useRestaurantCatalog()
// 地图预览与正式目录各自持有条件，组件只负责草稿和展示，不启动额外查询。
const filters = computed(() => props.filters ?? catalog.filters)
const groups = computed(() => [
  { key: 'price', label: '人均消费' },
  { key: 'venue', label: props.campusFilters ? '餐厅类型' : '场所类型' },
  { key: 'dining', label: '用餐方式' },
])
const activeGroup = ref('price')
const content = ref<globalThis.HTMLDivElement>()
const position = ref<Record<string, string>>({})
const prices = [20, 30, 50, 100]
const count = computed(
  () =>
    Number(filters.value.maxPrice !== null) +
    (props.campusFilters
      ? Number(!!filters.value.restaurantType) + Number(!!filters.value.diningMode)
      : 0),
)
const draft = reactive<{
  maxPrice: number | null
  restaurantType: NonNullable<CatalogFilters['restaurantType']>
  diningMode: NonNullable<CatalogFilters['diningMode']>
}>({
  restaurantType: '',
  diningMode: '',
  maxPrice: null,
})
const dialog = ref<globalThis.HTMLDialogElement>()
const trigger = ref<globalThis.HTMLButtonElement>()
const opened = ref(false)
const id = 'advanced-filters-' + useId()
let originalOverflow: string | undefined

/** 保留原生顶层的焦点约束，按筛选栏定位而非居中；避免地图 SDK 层级及裁切影响。 */
function placeDropdown() {
  const button = trigger.value
  if (!button) return
  const bar = button.closest('.filter-toolbar, .map-filter-toolbar') ?? button
  const rect = bar.getBoundingClientRect()
  const width = Math.min(640, globalThis.innerWidth - 24)
  const left = Math.max(12, Math.min(rect.right - width, globalThis.innerWidth - width - 12))
  // 视口过矮时先滚动筛选栏，再保留可滚动选项区和固定操作区。
  const top = Math.max(8, Math.min(rect.bottom + 6, globalThis.innerHeight - 240))
  position.value = {
    left: `${left}px`,
    top: `${top}px`,
    width: `${width}px`,
    maxHeight: `${globalThis.innerHeight - top - 12}px`,
    '--filter-top': `${top}px`,
  }
}
function chooseGroup(key: string) {
  activeGroup.value = key
  const section = content.value?.querySelector<globalThis.HTMLElement>(`[data-group="${key}"]`)
  if (section && content.value)
    content.value.scrollTo({
      top: section.offsetTop - 18,
      behavior: globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches
        ? 'instant'
        : 'smooth',
    })
}
/** 手动滚动时目录跟随当前段落，目录按钮不伪装成只显示单面板的 tabs。 */
function syncGroup() {
  if (!content.value) return
  const edge = content.value.getBoundingClientRect().top + 32
  const sections = [...content.value.querySelectorAll<globalThis.HTMLElement>('[data-group]')]
  const current = sections.filter((section) => section.getBoundingClientRect().top <= edge).at(-1)
  if (current?.dataset.group) activeGroup.value = current.dataset.group
}
function open() {
  if (opened.value) return
  Object.assign(draft, {
    restaurantType: filters.value.restaurantType || '',
    diningMode: filters.value.diningMode || '',
    maxPrice: filters.value.maxPrice,
  })
  trigger.value?.scrollIntoView?.({ block: 'nearest' })
  placeDropdown()
  dialog.value?.showModal()
  opened.value = true
  activeGroup.value = 'price'
  void nextTick(() => {
    if (content.value) content.value.scrollTop = 0
  })
  globalThis.addEventListener('resize', placeDropdown)
  globalThis.visualViewport?.addEventListener('resize', placeDropdown)
  originalOverflow = globalThis.document.documentElement.style.overflow
  globalThis.document.documentElement.style.overflow = 'hidden'
}

/** 关闭、Escape及卸载均释放背景锁；取消时草稿丢弃，下次从已应用状态回显。 */
function cleanup() {
  opened.value = false
  globalThis.removeEventListener('resize', placeDropdown)
  globalThis.visualViewport?.removeEventListener('resize', placeDropdown)
  if (originalOverflow !== undefined) {
    globalThis.document.documentElement.style.overflow = originalOverflow
    originalOverflow = undefined
  }
}
function close() {
  dialog.value?.close()
  cleanup()
  trigger.value?.focus({ preventScroll: true })
}
function apply() {
  Object.assign(filters.value, {
    maxPrice: draft.maxPrice,
    // 已移除距离入口，确认筛选时清除旧会话残留的距离限制。
    maxDistanceMeters: null,
  })
  // 仅首页开放新条件；保持旧消费者的字段集合，不把占位条件泄漏到地图筛选。
  if (props.campusFilters) {
    if (draft.restaurantType || filters.value.restaurantType !== undefined)
      filters.value.restaurantType = draft.restaurantType
    if (draft.diningMode || filters.value.diningMode !== undefined)
      filters.value.diningMode = draft.diningMode
  }
  close()
}
function reset() {
  Object.assign(draft, {
    maxPrice: null,
    restaurantType: '',
    diningMode: '',
  })
}
function backdrop(event: globalThis.MouseEvent) {
  if (event.target !== dialog.value) return
  const rect = dialog.value.getBoundingClientRect()
  if (
    event.clientX < rect.left ||
    event.clientX > rect.right ||
    event.clientY < rect.top ||
    event.clientY > rect.bottom
  )
    close()
}
onUnmounted(cleanup)
</script>

<template>
  <div class="advanced-filter-entry">
    <button
      ref="trigger"
      class="more-filters"
      type="button"
      :class="{ active: count > 0 }"
      :aria-expanded="opened"
      :aria-controls="id"
      aria-haspopup="dialog"
      @click="open"
    >
      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path
          d="M3 6h18M3 12h18M3 18h18M8 3v6m8 0v6m-9 0v6"
          stroke="currentColor"
          stroke-width="1.5"
          stroke-linecap="round"
        />
      </svg>
      更多筛选<span class="filter-chevron" :class="{ opened }" aria-hidden="true">⌄</span
      ><span v-if="count" class="filter-count" :aria-label="`${count}个高级条件已生效`">{{
        count
      }}</span>
    </button>
    <Teleport to="body">
      <dialog
        :id="id"
        ref="dialog"
        class="advanced-filter-dialog"
        :style="position"
        :aria-labelledby="id + '-title'"
        @cancel.prevent="close"
        @close="cleanup"
        @click="backdrop"
      >
        <header>
          <h2 :id="id + '-title'">更多筛选</h2>
          <button type="button" aria-label="关闭更多筛选" @click="close">关闭 ×</button>
        </header>
        <div class="advanced-filter-layout">
          <nav class="filter-directory" aria-label="筛选条件目录">
            <button
              v-for="group in groups"
              :key="group.key"
              type="button"
              :aria-current="activeGroup === group.key ? 'true' : undefined"
              @click="chooseGroup(group.key)"
            >
              {{ group.label }}
              <small v-if="!campusFilters && (group.key === 'venue' || group.key === 'dining')"
                >待开放</small
              >
            </button>
          </nav>
          <div ref="content" class="advanced-filter-body" @scroll="syncGroup">
            <fieldset data-group="price">
              <legend>人均消费</legend>
              <div class="advanced-choices">
                <button
                  type="button"
                  :aria-pressed="draft.maxPrice === null"
                  @click="draft.maxPrice = null"
                >
                  不限
                </button>
                <button
                  v-for="price in prices"
                  :key="price"
                  type="button"
                  :aria-pressed="draft.maxPrice === price"
                  @click="draft.maxPrice = price"
                >
                  {{ price }} 元以下
                </button>
              </div>
              <p>包含所选金额；已填报金额和估值均可筛选，价格未知的餐厅将排除。</p>
            </fieldset>
            <!-- 首页启用兼容筛选；地图暂保留原占位，避免列表与点位使用不同口径。 -->
            <template v-if="campusFilters">
              <fieldset data-group="venue">
                <legend>餐厅类型</legend>
                <div class="advanced-choices">
                  <button
                    type="button"
                    :aria-pressed="!draft.restaurantType"
                    @click="draft.restaurantType = ''"
                  >
                    全部
                  </button>
                  <button
                    type="button"
                    :aria-pressed="draft.restaurantType === 'REGULAR'"
                    @click="draft.restaurantType = 'REGULAR'"
                  >
                    餐厅
                  </button>
                  <button
                    type="button"
                    :aria-pressed="draft.restaurantType === 'CANTEEN'"
                    @click="draft.restaurantType = 'CANTEEN'"
                  >
                    食堂
                  </button>
                </div>
              </fieldset>
              <fieldset data-group="dining">
                <legend>用餐方式</legend>
                <div class="advanced-choices">
                  <button
                    type="button"
                    :aria-pressed="!draft.diningMode"
                    @click="draft.diningMode = ''"
                  >
                    不限
                  </button>
                  <button
                    type="button"
                    :aria-pressed="draft.diningMode === 'dineIn'"
                    @click="draft.diningMode = 'dineIn'"
                  >
                    支持堂食
                  </button>
                  <button
                    type="button"
                    :aria-pressed="draft.diningMode === 'delivery'"
                    @click="draft.diningMode = 'delivery'"
                  >
                    支持外卖
                  </button>
                </div>
                <p>仅显示已确认支持的商家，未填写的资料不参与筛选。</p>
              </fieldset>
            </template>
            <template v-else>
              <fieldset disabled class="planned-filters" data-group="venue">
                <legend>场所类型 <small>待开放</small></legend>
                <div class="advanced-choices">
                  <button type="button" disabled>食堂</button
                  ><button type="button" disabled>非食堂商家</button>
                </div>
              </fieldset>
              <fieldset disabled class="planned-filters" data-group="dining">
                <legend>用餐方式 <small>待开放</small></legend>
                <div class="advanced-choices">
                  <button type="button" disabled>堂食</button
                  ><button type="button" disabled>外卖</button>
                </div>
                <p>资料核实后开放；同一家店可以同时支持两种方式。</p>
              </fieldset>
            </template>
          </div>
        </div>
        <footer>
          <button class="reset-advanced" type="button" @click="reset">重置</button>
          <button class="apply-advanced" type="button" @click="apply">确定</button>
        </footer>
      </dialog>
    </Teleport>
  </div>
</template>

<style scoped src="./restaurant-advanced-filters.css"></style>
