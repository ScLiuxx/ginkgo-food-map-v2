<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, useId, watch } from 'vue'
import {
  campusDistance,
  filterRestaurants,
  useRestaurantCatalog,
  MapRestaurantCard,
  RestaurantAdvancedFilters,
  fetchRestaurant,
  fetchRestaurantCategories,
} from '@/modules/restaurant'
import type { RestaurantReadModel } from '@/modules/restaurant'
import { useViewport } from '../model/viewport'
import { useMapSelection } from '../model/selection'
import AMapCanvas from './AMapCanvas.vue'
import { mapExamples, navigationUrl, placeNavigationUrl } from '../model/map'
import { usePlaceSearch } from '../model/placeSearch'
import type { MapFocus, MapPoint } from '../api/mapApi'
import PlaceSearchResults from './PlaceSearchResults.vue'
import { SubmitRestaurantButton } from '@/modules/restaurant'
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
const selection = useMapSelection(props.preview ? 'demo-1' : null)
const selectedId = selection.activeId
const mobile = ref(globalThis.matchMedia?.('(max-width: 700px)').matches ?? false)
const sheetSnap = ref<'collapsed' | 'half' | 'full'>('collapsed')
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
const placesAnchor = ref<globalThis.HTMLDivElement>()
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
const viewport = useViewport(filters, !props.preview)
const searching = computed(() => !props.preview && Boolean(filters.keyword.trim()))
const places = usePlaceSearch(() => filters.keyword, !props.preview)
const focusTarget = ref<MapFocus | null>(null)
let focusSequence = 0
const remoteSelection = ref<RestaurantReadModel | null>(null)
let selectionSequence = 0
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
const selected = computed(
  () =>
    restaurants.value.find((r) => r.id === selectedId.value) ??
    (remoteSelection.value?.id === selectedId.value ? remoteSelection.value : null),
)
const selectedPlace = computed(
  () => places.records.value.find((place) => `amap:${place.id}` === selectedId.value) ?? null,
)
const hasSelection = computed(() => Boolean(selected.value || selectedPlace.value))
// 已经显示在本站当前页的同一店铺不再重复列出；不确定匹配关系的地点保持独立。
const externalRows = computed(() =>
  places.records.value.filter(
    (place) => !place.match || !restaurants.value.some((row) => row.id === place.match!.id),
  ),
)
const canvasPoints = computed<MapPoint[]>(() => [
  ...viewport.points.value,
  ...(searching.value
    ? externalRows.value.map((place): MapPoint => ({
        id: `amap:${place.id}`,
        kind: 'amap',
        name: place.name,
        longitude: place.longitude,
        latitude: place.latitude,
        score: null,
        pricePerPerson: null,
        businessStatus: 'UNKNOWN',
      }))
    : []),
])
const highlightedId = computed(() =>
  selectedPlace.value?.match &&
  restaurants.value.some((row) => row.id === selectedPlace.value!.match!.id)
    ? selectedPlace.value.match.id
    : selectedId.value,
)
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
    Number(!!filters.diningMode),
)
const loading = computed(() => !props.preview && viewport.loading.value)
const error = computed(() => (props.preview ? null : viewport.error.value))
watch([restaurants, places.records], ([rows, external]) => {
  const visible = (id: string) =>
    rows.some((r) => r.id === id) ||
    viewport.points.value.some((p) => p.id === id) ||
    external.some((place) => `amap:${place.id}` === id)
  if (selection.pinnedId.value && !visible(selection.pinnedId.value))
    selection.pinnedId.value = null
  if (selection.hoveredId.value && !visible(selection.hoveredId.value))
    selection.hoveredId.value = null
  if (props.preview && !selectedId.value && rows[0]) selection.pin(rows[0].id)
})
onMounted(() => {
  media?.addEventListener('change', updateMobile)
  if (globalThis.ResizeObserver && filterArea.value) {
    filterObserver = new globalThis.ResizeObserver(() => {
      filterBottom.value = filterArea.value?.getBoundingClientRect().bottom ?? 140
    })
    filterObserver.observe(filterArea.value)
  }
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
/** 原生dialog约束焦点；关闭和卸载都恢复背景滚动，草稿只有确认才写回查询。 */
function selectRestaurant(id: string) {
  selection.pin(id)
  sheetSnap.value = 'collapsed'
  if (searching.value || mobile.value) {
    const point = canvasPoints.value.find((row) => row.id === id)
    if (point)
      focusTarget.value = {
        longitude: point.longitude,
        latitude: point.latitude,
        sequence: ++focusSequence,
        preserveResults: mobile.value,
      }
  }
}
/** 两类结果共用侧栏滚动区；直接到高德分组，不滚动整页或改变查询条件。 */
async function showPlaceResults() {
  if (mobile.value) sheetSnap.value = 'full'
  await nextTick()
  if (!placesAnchor.value || !resultPanel.value) return
  const top =
    resultPanel.value.scrollTop +
    placesAnchor.value.getBoundingClientRect().top -
    resultPanel.value.getBoundingClientRect().top
  resultPanel.value.scrollTo({ top, behavior: 'auto' })
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
/** 悬停与固定使用同一详情读取，关闭后使迟到响应失效，不能重新弹出卡片。 */
watch(selectedId, (id) => {
  const ticket = ++selectionSequence
  remoteSelection.value = null
  if (
    id &&
    !id.startsWith('amap:') &&
    !props.preview &&
    !restaurants.value.some((r) => r.id === id)
  ) {
    void fetchRestaurant(id)
      .then((row) => {
        if (ticket === selectionSequence && selectedId.value === id) remoteSelection.value = row
      })
      .catch(() => {
        if (ticket === selectionSequence) selection.close()
      })
  }
})
onUnmounted(() => {
  media?.removeEventListener('change', updateMobile)
  filterObserver?.disconnect()
  selectionSequence++
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
      <RouterLink class="map-mobile-brand" to="/" aria-label="泥电美食地图首页">泥电</RouterLink>
      <h1>地图找店</h1>
      <RouterLink v-if="preview" to="/map">返回真实餐厅 →</RouterLink>
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
          placeholder="搜索餐厅、地址"
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
        >
          <p v-if="searching" class="map-search-summary">
            <span>全站搜索 · 本站 + 高德</span
            ><button aria-controls="map-place-results" @click="showPlaceResults">
              {{
                places.error.value
                  ? '高德地点暂不可用'
                  : places.loading.value
                    ? '高德搜索中…'
                    : `高德地点 ${places.total.value}`
              }}
              ↓
            </button>
          </p>
          <div class="map-result-heading">
            <h2>
              {{
                searching
                  ? '本站已收录'
                  : preview || viewport.bounds.value
                    ? '此区域的餐厅'
                    : '餐厅目录'
              }}
            </h2>
            <span role="status">{{
              loading
                ? '正在加载…'
                : preview
                  ? `${restaurants.length} 家示例餐厅`
                  : `共 ${viewport.total.value} 家 · 当前页 ${restaurants.length} 家`
            }}</span>
          </div>
          <p v-if="viewport.searchNotice.value && !loading" role="status">
            {{ viewport.searchNotice.value }}
          </p>
          <p v-if="error" class="map-empty" role="alert">
            {{ error }} <button @click="viewport.load">重试</button>
          </p>
          <div v-else class="map-card-list">
            <MapRestaurantCard
              v-for="restaurant in restaurants"
              :key="restaurant.id"
              :restaurant="restaurant"
              :selected="restaurant.id === highlightedId"
              @select="selectRestaurant(restaurant.id)"
            />
            <p v-if="!restaurants.length && !loading" class="map-empty">
              {{
                searching
                  ? '本站没有匹配的餐厅，可继续查看下方高德地点。'
                  : '没有符合条件的餐厅，试试其他分类或筛选。'
              }}
            </p>
          </div>
          <nav
            v-if="!preview && viewport.pages.value > 1"
            class="map-pagination"
            aria-label="餐厅分页"
          >
            <button
              :disabled="viewport.page.value === 1 || loading"
              @click="viewport.goToPage(viewport.page.value - 1)"
            >
              上一页</button
            ><span>{{ viewport.page.value }} / {{ viewport.pages.value }}</span
            ><button
              :disabled="viewport.page.value === viewport.pages.value || loading"
              @click="viewport.goToPage(viewport.page.value + 1)"
            >
              下一页
            </button>
          </nav>
          <p class="map-data-note">
            {{
              preview
                ? '示意数据 · 餐厅与位置均为虚构，不用于导航'
                : searching
                  ? '全站搜索，不受当前地图范围限制。地图展示当前页本站结果和本页高德地点；点击结果移到餐厅位置。'
                  : '移动或缩放结束后自动更新；未加载地图时显示全部餐厅目录。'
            }}
          </p>
          <p
            v-if="!preview && restaurants.some((r) => r.tags.includes('OSM测试数据'))"
            class="map-data-note"
          >
            © OpenStreetMap contributors ·
            <a
              href="https://www.openstreetmap.org/copyright"
              target="_blank"
              rel="noopener noreferrer"
              >ODbL-1.0</a
            >
          </p>
          <div v-if="searching" id="map-place-results" ref="placesAnchor">
            <PlaceSearchResults
              :keyword="filters.keyword"
              :can-submit="
                !preview &&
                !loading &&
                !error &&
                viewport.resultsCurrent.value &&
                viewport.total.value === 0 &&
                !places.loading.value &&
                !places.error.value
              "
              :records="externalRows"
              :hidden-count="places.records.value.length - externalRows.length"
              :loading="places.loading.value"
              :error="places.error.value"
              :total="places.total.value"
              :page="places.page.value"
              :pages="places.pages.value"
              :selected="selectedId"
              @select="selectRestaurant"
              @retry="places.load"
              @page-change="places.goToPage"
            />
          </div>
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
          :points="canvasPoints"
          :selected="highlightedId"
          :focus="focusTarget"
          @move="viewport.move"
          @select="selectRestaurant"
          @hover="selection.hover"
          @leave="selection.leave"
        />
        <p v-if="!preview && loading" class="map-auto-status" role="status">
          {{ searching ? '正在搜索本站餐厅…' : '正在更新此区域…' }}
        </p>
        <p v-if="!preview && error" class="map-auto-status" role="alert">
          {{ searching ? '本站搜索失败，请重试。' : '区域更新失败，当前显示上次结果。'
          }}<button @click="viewport.load">重试</button>
        </p>
        <p v-if="!preview && viewport.truncated.value" class="map-truncation" role="status">
          此区域餐厅较多，最多显示100个点位，请放大地图查看更小范围。
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
                <RouterLink v-if="!preview" :to="`/restaurants/${selected.id}`">查看详情</RouterLink
                ><button v-else disabled>查看详情</button
                ><a
                  v-if="navigationUrl(selected)"
                  :href="navigationUrl(selected)!"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="map-navigate"
                  title="在高德打开此餐厅，由高德处理定位与路线"
                  >在高德中打开 ↗</a
                ><button v-else class="map-navigate" disabled>
                  {{ preview ? '在高德中打开 ↗' : '位置待确认' }}
                </button>
              </div>
              <small v-if="!preview && navigationUrl(selected)" class="navigation-origin"
                >定位与路线由高德地图处理</small
              >
            </template>
            <template v-else-if="selectedPlace">
              <div class="map-place-detail">
                <small
                  >高德地点 ·
                  {{
                    selectedPlace.matchState === 'matched'
                      ? '本站已收录'
                      : selectedPlace.matchState === 'checking'
                        ? '正在核对收录状态'
                        : selectedPlace.matchState === 'unavailable'
                          ? '收录状态待核实'
                          : '暂未匹配本站'
                  }}</small
                >
                <h3>{{ selectedPlace.name }}</h3>
                <p>{{ selectedPlace.address || '地址待补充' }}</p>
                <p>
                  {{ [selectedPlace.district, selectedPlace.category].filter(Boolean).join(' · ') }}
                </p>
              </div>
              <div class="map-selection-actions">
                <RouterLink
                  v-if="selectedPlace.match"
                  :to="`/restaurants/${selectedPlace.match.id}`"
                  >查看本站详情</RouterLink
                >
                <SubmitRestaurantButton
                  v-else-if="selectedPlace.matchState === 'unmatched'"
                  :place="selectedPlace"
                />
                <span v-else>正在核实收录状态，暂不能提交</span>
                <a
                  v-if="placeNavigationUrl(selectedPlace)"
                  :href="placeNavigationUrl(selectedPlace)!"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="map-navigate"
                  >在高德中打开 ↗</a
                >
                <button v-else class="map-navigate" disabled>位置待确认</button>
              </div>
              <small class="navigation-origin">来源：高德地图 · 定位与路线由高德处理</small>
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
