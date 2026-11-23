<script setup lang="ts">
import ResourceLink from '@/shared/ui/ResourceLink.vue'
import AppLink from '@/shared/ui/AppLink.vue'
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  onUnmounted,
  reactive,
  ref,
  useId,
  watch,
} from 'vue'
import {
  campusDistance,
  filterRestaurants,
  useRestaurantCatalog,
  MapRestaurantCard,
  RestaurantAdvancedFilters,
  fetchRestaurantCategories,
} from '@/modules/restaurant'
import { useViewport } from '../model/viewport'
import { useMapSelection } from '../model/selection'
import { useMapBrowsing } from '../model/browsing.store'
import AMapCanvas from './AMapCanvas.vue'
import { mapExamples, navigationUrl } from '../model/map'
import type { MapCamera, MapFocus } from '../api/mapApi'
import type { CatalogFilters } from '@/modules/restaurant'
import MapIllustration from './MapIllustration.vue'
import NearbyLocation from '@/modules/restaurant/ui/NearbyLocation.vue'
import MapBottomSheet from './MapBottomSheet.vue'

/** 预览只操作本地样例；正式页复用目录筛选状态并读取真实视口接口。 */
const props = withDefaults(defineProps<{ preview?: boolean }>(), {
  preview: false,
})
const catalog = useRestaurantCatalog()
if (!props.preview) catalog.pause()
const browsing = useMapBrowsing()
const restored = props.preview ? null : browsing.snapshot
if (restored) {
  // 首页也会改共享条件；回到地图时恢复自己的快照，并清掉快照未选择的可选条件。
  Object.assign(catalog.filters, {
    distanceOrigin: undefined,
    latitude: undefined,
    longitude: undefined,
    restaurantType: undefined,
    diningMode: undefined,
    hasReviews: undefined,
    ...restored.filters,
  })
}
const filters = props.preview
  ? reactive<CatalogFilters>({
      keyword: '',
      category: '',
      sort: 'score',
      hideClosed: false,
      maxPrice: null,
      maxDistanceMeters: null,
    })
  : catalog.filters
const selection = useMapSelection(props.preview ? 'demo-1' : (restored?.selectedId ?? null))
const selectedId = selection.activeId
const mobile = ref(globalThis.matchMedia?.('(max-width: 700px)').matches ?? false)
const sheetSnap = ref<'collapsed' | 'half' | 'full'>(restored?.sheetSnap ?? 'collapsed')
const sheetHeight = ref(148)
const filterArea = ref<globalThis.HTMLElement>()
const filterBottom = ref(140)
let filterObserver: globalThis.ResizeObserver | undefined
const sheetId = 'map-sheet-' + useId()
const media = globalThis.matchMedia?.('(max-width: 700px)')
function updateMobile() {
  mobile.value = media?.matches ?? false
}
const resultPanel = ref<globalThis.HTMLElement>()
const canvas = ref<InstanceType<typeof AMapCanvas>>()
const camera = ref<MapCamera | null>(restored?.camera ?? null)
const listScroll = ref(restored?.scrollTop ?? 0)
let pendingScroll = Boolean(restored)
let restoreResults = Boolean(restored)
let leaving = false
const listVisible = () => !mobile.value || !hasSelection.value || sheetSnap.value !== 'collapsed'
/** 手机选店卡片遮住列表时延后恢复滚动；显示列表后再应用，避免隐藏DOM把位置归零。 */
function restoreListScroll() {
  if (leaving || !pendingScroll || !listVisible() || !resultPanel.value) return
  resultPanel.value.scrollTop = listScroll.value
  pendingScroll = false
}
function rememberListScroll() {
  if (listVisible() && resultPanel.value) listScroll.value = resultPanel.value.scrollTop
}
const selectionPanel = ref<globalThis.HTMLDivElement>()
const selectionHeight = ref(225)
/** 浮层随长店名自然增长，仅调整底部面板，不能通过压缩地图改变候选范围。 */
watch(selectionPanel, (panel, _previous, onCleanup) => {
  if (!panel || !globalThis.ResizeObserver) return
  const observer = new globalThis.ResizeObserver(() => {
    const height = Math.ceil(panel.getBoundingClientRect().height)
    // 展开列表时选店卡片暂时隐藏，不能把隐藏高度0当成下一次收起所需的高度。
    if (height) selectionHeight.value = height
  })
  observer.observe(panel)
  onCleanup(() => observer.disconnect())
})
const viewport = useViewport(filters, !props.preview, restored?.viewport)
const searching = computed(() => !props.preview && Boolean(filters.keyword.trim()))
const focusTarget = ref<MapFocus | null>(null)
let focusSequence = 0
const categories = computed(() =>
  props.preview
    ? ['火锅', '川菜', '小吃', '快餐', '烧烤'].map((name) => ({
        code: name,
        name,
      }))
    : // 与首页一致：食堂是场所类型，不展示为顶部美食品类；不删除原始分类或商家记录。
      catalog.categories.filter(
        (category) => category.code !== 'CANTEEN' && category.name !== '食堂',
      ),
)
const restaurants = computed(() => {
  if (!props.preview) return viewport.rows.value
  return filterRestaurants(mapExamples, filters).filter(
    (r) =>
      (filters.maxPrice === null || (r.price !== null && r.price <= filters.maxPrice)) &&
      (filters.maxDistanceMeters === null ||
        (campusDistance(r) !== null && campusDistance(r)! * 1000 <= filters.maxDistanceMeters)),
  )
})
/** 地图和列表均来自同一批候选，点选无需再跨请求补查其他店铺。 */
const selected = computed(() => restaurants.value.find((r) => r.id === selectedId.value) ?? null)
const hasSelection = computed(() => Boolean(selected.value))
watch([mobile, sheetSnap, hasSelection], async () => {
  await nextTick()
  restoreListScroll()
})
// 返回时相同条件的重新验证保留阅读位置；主动换范围或筛选仍从列表顶部开始。
watch(
  [filters, viewport.bounds],
  () => {
    restoreResults = false
  },
  { deep: true, flush: 'sync' },
)
const canvasPoints = computed(() => viewport.points.value)
const highlightedId = selectedId
watch(
  () => filters.keyword.trim(),
  () => {
    if (!props.preview) {
      selection.close()
      focusTarget.value = null
    }
  },
)
const advancedCount = computed(
  () =>
    Number(filters.maxPrice !== null) +
    Number(filters.maxDistanceMeters !== null) +
    Number(!!filters.restaurantType) +
    Number(filters.hasReviews != null) +
    Number(!!filters.diningMode),
)
const loading = computed(() => !props.preview && viewport.loading.value)
const error = computed(() => (props.preview ? null : viewport.error.value))
/** 范围或条件成功切换后重置卡片滚动，仍在集合内的选店保持有效。 */
watch(restaurants, async (rows) => {
  const visible = (id: string) => rows.some((r) => r.id === id)
  if (selection.pinnedId.value && !visible(selection.pinnedId.value)) {
    selection.pinnedId.value = null
    focusTarget.value = null
  }
  if (selection.hoveredId.value && !visible(selection.hoveredId.value))
    selection.hoveredId.value = null
  if (props.preview && !selectedId.value && rows[0]) selection.pin(rows[0].id)
  await nextTick()
  if (restoreResults) {
    pendingScroll = true
    restoreListScroll()
  } else {
    listScroll.value = 0
    pendingScroll = false
    if (resultPanel.value) resultPanel.value.scrollTop = 0
  }
})
onMounted(async () => {
  media?.addEventListener('change', updateMobile)
  if (globalThis.ResizeObserver && filterArea.value) {
    filterObserver = new globalThis.ResizeObserver(() => {
      filterBottom.value = filterArea.value?.getBoundingClientRect().bottom ?? 140
    })
    filterObserver.observe(filterArea.value)
  }
  await nextTick()
  if (leaving) return
  restoreListScroll()
  if (!props.preview) {
    void viewport.load()
    if (!catalog.categories.length)
      void fetchRestaurantCategories()
        .then((rows) => {
          catalog.categories = rows
        })
        .catch(() => {})
  }
})
/** 点选只定位并高亮，电脑和手机都保留原候选范围，避免自动放大触发重查。 */
function selectRestaurant(id: string) {
  selection.pin(id)
  sheetSnap.value = 'collapsed'
  if (!props.preview) {
    const point = canvasPoints.value.find((row) => row.id === id)
    if (point)
      focusTarget.value = {
        longitude: point.longitude,
        latitude: point.latitude,
        sequence: ++focusSequence,
        preserveResults: true,
      }
  }
}
/** Tab在卡片内部移动时保持预览；焦点移出整个卡片后才安排关闭。 */
function onCardFocusOut(event: globalThis.FocusEvent) {
  if (
    event.relatedTarget instanceof globalThis.Node &&
    (event.currentTarget as globalThis.HTMLElement).contains(event.relatedTarget)
  )
    return
  selection.leave()
}
onBeforeUnmount(() => {
  // 父组件先取快照，再由子组件正常销毁地图；预览不读写真实浏览记录。
  rememberListScroll()
  if (!props.preview)
    browsing.remember({
      filters: { ...filters },
      camera: canvas.value?.captureCamera() ?? camera.value,
      viewport: viewport.snapshot(),
      selectedId: selection.pinnedId.value,
      sheetSnap: sheetSnap.value,
      scrollTop: listScroll.value,
    })
  leaving = true
})
onUnmounted(() => {
  media?.removeEventListener('change', updateMobile)
  filterObserver?.disconnect()
  if (!props.preview) catalog.cancelPending()
})
</script>
<template>
  <section
    class="map-workspace"
    :class="{ 'is-preview': preview, 'sheet-full': mobile && sheetSnap === 'full' }"
    :style="{
      '--map-selection-height': `${hasSelection ? selectionHeight : 0}px`,
      '--map-sheet-height': `${mobile ? sheetHeight : 0}px`,
      '--map-filter-bottom': `${filterBottom}px`,
    }"
    aria-label="地图找店"
  >
    <div class="map-topline">
      <AppLink class="map-mobile-brand" to="/" aria-label="泥电美食地图首页">泥电</AppLink>
      <AppLink v-if="preview" to="/map">返回真实餐厅 →</AppLink>
    </div>
    <div class="map-layout">
      <div class="map-search">
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <circle cx="10.5" cy="10.5" r="6.5" stroke="currentColor" stroke-width="1.6" />
          <path d="m16 16 4 4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
        </svg>
        <input
          v-model="filters.keyword"
          type="search"
          maxlength="100"
          aria-label="搜索餐厅、地址"
        />
      </div>
      <div ref="filterArea" class="map-filter-area">
        <nav class="map-categories" aria-label="美食分类">
          <button :aria-pressed="!filters.category" @click="filters.category = ''">全部美食</button
          ><button
            v-for="category in categories"
            :key="category.code"
            :aria-pressed="filters.category === category.code"
            @click="filters.category = category.code"
          >
            {{ category.name }}
          </button>
        </nav>
        <div class="map-filter-toolbar">
          <NearbyLocation v-if="!preview" :filters="filters" />
          <button
            class="map-hide-closed"
            :aria-pressed="filters.hideClosed"
            @click="filters.hideClosed = !filters.hideClosed"
          >
            <span aria-hidden="true"></span>
            隐藏停业餐厅</button
          ><RestaurantAdvancedFilters
            :filters="filters"
            :categories="categories"
            campus-filters
            map-filters
          />
        </div>
        <div v-if="advancedCount" class="map-applied">
          <button v-if="filters.hasReviews != null" @click="filters.hasReviews = null">
            {{ filters.hasReviews ? '有点评' : '无点评' }} ×
          </button>
          <button v-if="filters.maxPrice !== null" @click="filters.maxPrice = null">
            人均 ≤ {{ filters.maxPrice }}元 ×</button
          ><button
            v-if="filters.maxDistanceMeters !== null"
            @click="filters.maxDistanceMeters = null"
          >
            直线 ≤ {{ filters.maxDistanceMeters }}米 ×
          </button>
          <button v-if="filters.restaurantType" @click="filters.restaurantType = ''">
            {{ filters.restaurantType === 'CANTEEN' ? '食堂' : '普通餐厅' }} ×
          </button>
          <button v-if="filters.diningMode" @click="filters.diningMode = ''">
            {{ filters.diningMode === 'dineIn' ? '支持堂食' : '支持外卖' }} ×
          </button>
        </div>
      </div>
      <!-- 移动端将同一份真实结果移入面板，避免复制列表、重复请求或丢失滚动状态。 -->
      <Teleport :to="`#${sheetId}-results`" :disabled="!mobile" defer>
        <aside
          id="map-restaurant-results"
          ref="resultPanel"
          class="map-result-panel"
          aria-label="餐厅结果"
          @scroll="rememberListScroll"
        >
          <div class="map-result-heading">
            <h2>
              {{ searching ? '此区域的搜索结果' : '此区域的餐厅' }}
            </h2>
            <span role="status">{{
              loading
                ? '正在加载…'
                : preview
                  ? `${restaurants.length} 家示例餐厅`
                  : !viewport.bounds.value
                    ? '等待地图范围…'
                    : `区域内 ${viewport.total.value} 家 · 当前展示 ${restaurants.length} 家`
            }}</span>
          </div>
          <p v-if="viewport.searchNotice.value && !loading" role="status">
            {{ viewport.searchNotice.value }}
          </p>
          <p v-if="error" class="map-empty" role="alert">
            {{ error }} <button @click="viewport.load">重试</button>
          </p>
          <div class="map-card-list">
            <MapRestaurantCard
              v-for="restaurant in restaurants"
              :key="restaurant.id"
              :restaurant="restaurant"
              :selected="restaurant.id === highlightedId"
              @select="selectRestaurant(restaurant.id)"
            />
            <p v-if="!restaurants.length && !loading && !error" class="map-empty">
              {{
                searching
                  ? '当前地图范围内没有匹配的餐厅，可移动地图或调整关键词。'
                  : !preview && !viewport.bounds.value
                    ? '等待地图确定范围；也可返回首页浏览餐厅。'
                    : '当前范围内没有符合条件的餐厅，可移动地图或调整筛选。'
              }}
            </p>
          </div>
          <p v-if="!preview && viewport.truncated.value" class="map-data-note" role="status">
            当前展示本区域符合条件的前 {{ restaurants.length }} 家，放大或移动地图可查找更多店铺。
          </p>
          <p class="map-data-note">
            {{
              preview
                ? '示意数据 · 餐厅与位置均为虚构，不用于导航'
                : '搜索、筛选只作用于当前地图范围；移动或缩放后一起更新地图和列表。'
            }}
          </p>
          <p
            v-if="!preview && restaurants.some((r) => r.tags.includes('OSM测试数据'))"
            class="map-data-note"
          >
            © OpenStreetMap contributors ·
            <ResourceLink
              href="https://www.openstreetmap.org/copyright"
              target="_blank"
              rel="noopener noreferrer"
              >ODbL-1.0</ResourceLink
            >
          </p>
        </aside>
      </Teleport>
      <div
        class="map-stage"
        :class="{ 'has-selection': hasSelection }"
        :inert="mobile && sheetSnap === 'full'"
        @keydown.esc="selection.close"
      >
        <MapIllustration
          v-if="preview"
          :ids="restaurants.map((r) => r.id)"
          :selected="selectedId"
          @select="selectRestaurant"
          @hover="selection.hover"
          @leave="selection.leave"
        />
        <AMapCanvas
          v-else
          ref="canvas"
          :initial-camera="restored?.viewport.bounds ? restored.camera : null"
          :points="canvasPoints"
          :selected="highlightedId"
          :focus="focusTarget"
          @move="viewport.move"
          @view="camera = $event"
          @select="selectRestaurant"
          @hover="selection.hover"
          @leave="selection.leave"
        />
        <p v-if="!preview && loading" class="map-auto-status" role="status">
          {{ searching ? '正在搜索本站餐厅…' : '正在更新此区域…' }}
        </p>
        <p v-if="!preview && error" class="map-auto-status" role="alert">
          区域更新失败，当前显示上次结果。<button @click="viewport.load">重试</button>
        </p>
        <Teleport :to="`#${sheetId}-selection`" :disabled="!mobile" defer>
          <div
            v-if="hasSelection"
            ref="selectionPanel"
            class="map-selection"
            aria-label="选中餐厅"
            @mouseenter="selection.keepOpen"
            @mouseleave="selection.leave"
            @focusin="selection.keepOpen"
            @focusout="onCardFocusOut"
          >
            <button class="map-popup-close" aria-label="关闭选中餐厅" @click="selection.close">
              ×
            </button>
            <template v-if="selected">
              <MapRestaurantCard
                :restaurant="selected"
                :selected="false"
                @select="mobile && (sheetSnap = 'half')"
              />
              <div class="map-selection-actions">
                <AppLink
                  v-if="!preview"
                  :to="{ path: `/restaurants/${selected.id}`, query: { from: 'map' } }"
                  >查看详情</AppLink
                ><button v-else disabled>查看详情</button
                ><ResourceLink
                  v-if="navigationUrl(selected)"
                  :href="navigationUrl(selected)!"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="map-navigate"
                  title="在高德打开此餐厅，由高德处理定位与路线"
                  >在高德中打开 ↗</ResourceLink
                ><button v-else class="map-navigate" disabled>
                  {{ preview ? '在高德中打开 ↗' : '位置待确认' }}
                </button>
              </div>
              <small v-if="!preview && navigationUrl(selected)" class="navigation-origin"
                >定位与路线由高德地图处理</small
              >
            </template>
          </div>
        </Teleport>
      </div>
      <!-- 常驻目标容器保证横竖屏切换时Teleport始终能找到接收点；桌面仅隐藏面板。 -->
      <MapBottomSheet
        v-show="mobile"
        v-model="sheetSnap"
        :list-id="`${sheetId}-results`"
        :selection-id="`${sheetId}-selection`"
        :selected="hasSelection"
        :selection-height="selectionHeight"
        title="餐厅结果"
        @resize="sheetHeight = $event"
      />
    </div>
  </section>
</template>
<style scoped src="./map-workspace.css"></style>
