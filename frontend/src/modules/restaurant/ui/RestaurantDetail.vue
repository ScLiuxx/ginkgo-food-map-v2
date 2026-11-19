<script setup lang="ts">
import ResourceLink from '@/shared/ui/ResourceLink.vue'
import AppLink from '@/shared/ui/AppLink.vue'
import axios from 'axios'
import { ReviewComposer, ReviewPanel } from '@/modules/review'
import RestaurantAlbum from './RestaurantAlbum.vue'
import WindowAlbum from './WindowAlbum.vue'
import FavoriteButton from './FavoriteButton.vue'
import { navigationUrl } from '@/modules/map'
import { computed, onUnmounted, ref, watch } from 'vue'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { fetchRestaurant } from '../api/restaurantApi'
import { fetchWindow } from '../api/canteenWindowApi'
import type { RestaurantReadModel } from '../model/restaurant.types'
import { priceLabel, ratingDimensions } from '../model/catalog.logic'
import { formatRating } from '@/shared/lib/formatRating'
import { useRoute, useRouter } from 'vue-router'
import { useRestaurantPicker } from '../model/picker.store'
import { diningDetails } from '../model/campusDining'
import CanteenWindows from './CanteenWindows.vue'
import RestaurantManagementActions from './RestaurantManagementActions.vue'
import { useRestaurantCatalog } from '../model/catalog.store'
import { isProvenanceTag } from './restaurantPresentation'
import { useReturnNavigation } from '@/shared/lib/useReturnNavigation'

const props = defineProps<{ id: string; window?: boolean }>()
const restaurant = ref<RestaurantReadModel | null>(null)
const dining = computed(() => (restaurant.value ? diningDetails(restaurant.value) : null))
const isCanteen = computed(() => dining.value?.kind === 'canteen')
const isWindow = computed(() => dining.value?.kind === 'window')
const navigation = computed(() => (restaurant.value ? navigationUrl(restaurant.value) : null))
const diningMethods = computed(() =>
  [
    restaurant.value?.supportsDineIn === true ? '堂食' : '',
    restaurant.value?.supportsDelivery === true ? '外卖' : '',
  ]
    .filter(Boolean)
    .join(' / '),
)
const failedDishes = ref<string[]>([])
// 相册自行判断空态与故障；只有成功读到空列表才收起媒体列，不把读取失败当作无图。
const albumVisible = ref(true)
const route = useRoute()
const router = useRouter()
const fromMap = computed(() => route.query.from === 'map')
const fromPicker = computed(() => route.query.from === 'picker')
const picker = useRestaurantPicker()
const { returnTarget, returnToPrevious } = useReturnNavigation(() =>
  isWindow.value && dining.value?.parent ? router.resolve(parentLink.value).fullPath : '/',
)
const backTarget = computed(() =>
  fromMap.value ? '/map' : fromPicker.value ? '/?picker=return' : returnTarget.value,
)
/** 地图/选店入口显式标记来源；普通点击回到原历史条目，直达或新标签页回到固定站内入口。
 * 保留修饰键和新标签页打开链接的行为，不把用户提供的任意地址作为返回目标。
 */
function returnToSource(event: globalThis.MouseEvent) {
  if (!fromMap.value && !fromPicker.value) {
    returnToPrevious(event)
    return
  }
  if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
  event.preventDefault()
  const previous = router.options.history.state.back
  if (fromPicker.value) {
    picker.pendingReturn = true
    if (previous === picker.originRoute && picker.confirmed) router.back()
    else void router.replace('/?picker=return')
  } else if (previous === '/map') router.back()
  else void router.replace('/map')
}
const displayScore = computed(() => formatRating(restaurant.value?.score))
const dimensionsExpanded = ref(false)
const dimensionsId = computed(() => `detail-dimensions-${props.window ? 'window-' : ''}${props.id}`)
/** 返回链接只使用真实父食堂ID；筛选来自窗口入口快照，直达窗口时回到其所属楼层。 */
const parentLink = computed(() => ({
  path: `/restaurants/${encodeURIComponent(dining.value?.parent?.id ?? '')}`,
  query: {
    floor:
      typeof route.query.fromFloor === 'string'
        ? route.query.fromFloor
        : (dining.value?.floor ?? ''),
  },
}))
const loading = ref(true)
const error = ref<string | null>(null)
let controller: globalThis.AbortController | undefined
let generation = 0
// 仅收起内部展示标签，不修改原始餐厅资料或学生点评正文。
const publicTags = computed(
  () => restaurant.value?.tags.filter((tag) => !isProvenanceTag(tag)) ?? [],
)
// 回执确认后分别刷新点评和统计，保留整个详情组件与讨论面板。
const reviewRevision = ref(0)
const catalog = useRestaurantCatalog()
const statisticsLoading = ref(false),
  statisticsError = ref('')
let statisticsController: globalThis.AbortController | undefined,
  statisticsGeneration = 0

/** 统计失败独立重试，不重新发布点评；同店组件保留，切店后拒绝晚到的旧统计。 */
async function refreshStatistics(): Promise<void> {
  const id = props.id,
    ticket = ++statisticsGeneration
  statisticsController?.abort()
  statisticsController = new globalThis.AbortController()
  const signal = statisticsController.signal
  statisticsLoading.value = true
  statisticsError.value = ''
  try {
    const fresh = await (props.window ? fetchWindow(id, signal) : fetchRestaurant(id, signal))
    if (ticket === statisticsGeneration && props.id === id) restaurant.value = fresh
  } catch (reason) {
    if (ticket !== statisticsGeneration || signal.aborted || props.id !== id) return
    if (axios.isAxiosError(reason) && reason.response?.status === 404) {
      void loadDetail()
      return
    }
    statisticsError.value = '评分刷新失败，当前显示上次读取的数据。请重试刷新。'
  } finally {
    if (ticket === statisticsGeneration) statisticsLoading.value = false
  }
}
function published() {
  reviewRevision.value++
  catalog.invalidate()
  void refreshStatistics()
}

/** 路由复用和失败重试都独立读取；取消旧请求，避免旧店资料覆盖新页面。 */
async function loadDetail(): Promise<void> {
  statisticsGeneration++
  statisticsController?.abort()
  statisticsLoading.value = false
  statisticsError.value = ''
  const requestGeneration = ++generation
  controller?.abort()
  controller = new globalThis.AbortController()
  const signal = controller.signal
  restaurant.value = null
  loading.value = true
  error.value = null
  failedDishes.value = []
  dimensionsExpanded.value = false
  try {
    const result = await (props.window
      ? fetchWindow(props.id, signal)
      : fetchRestaurant(props.id, signal))
    if (requestGeneration === generation) restaurant.value = result
  } catch (reason) {
    if (requestGeneration !== generation || signal.aborted) return
    // 只有真实404才展示“没有找到”，临时断网和服务故障必须允许重试。
    if (!axios.isAxiosError(reason) || reason.response?.status !== 404) {
      error.value = toErrorMessage(reason)
    }
  } finally {
    if (requestGeneration === generation) loading.value = false
  }
}
watch(
  () => [props.id, props.window],
  () => {
    void loadDetail()
  },
  { immediate: true },
)
onUnmounted(() => {
  statisticsGeneration++
  statisticsController?.abort()
  generation += 1
  controller?.abort()
})
</script>

<template>
  <div class="detail-page" :aria-busy="loading">
    <section v-if="loading" class="missing-restaurant" role="status">正在加载餐厅资料…</section>
    <section v-else-if="error" class="missing-restaurant" role="alert">
      <h1>餐厅资料暂时无法加载。</h1>
      <p>{{ error }}</p>
      <button type="button" class="primary-action" @click="loadDetail">重新加载</button>
      <AppLink :to="backTarget" @click.capture="returnToSource">返回</AppLink>
    </section>
    <article
      v-else-if="restaurant && dining"
      class="restaurant-detail"
      :class="`detail-${dining.kind}`"
      aria-labelledby="restaurant-title"
    >
      <nav class="detail-breadcrumb" aria-label="详情导航">
        <AppLink :to="backTarget" aria-label="返回" @click.capture="returnToSource"
          ><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 5-7 7 7 7" /></svg
        ></AppLink>
      </nav>

      <header class="detail-hero" :class="{ 'canteen-hero': isCanteen, 'has-album': albumVisible }">
        <WindowAlbum
          v-if="isWindow"
          :id="restaurant.id"
          :key="`window-${restaurant.id}`"
          :name="restaurant.name"
          compact
          @visibility="albumVisible = $event"
        />
        <RestaurantAlbum
          v-if="!isWindow"
          :key="restaurant.id"
          :restaurant-id="restaurant.id"
          :name="restaurant.name"
          compact
          @visibility="albumVisible = $event"
        />
        <div class="detail-heading">
          <div class="hero-topline">
            <h1 id="restaurant-title">{{ restaurant.name }}</h1>
            <FavoriteButton v-if="!isWindow" :restaurant-id="restaurant.id" icon-only />
          </div>
          <p v-if="isWindow" class="detail-affiliation">
            <AppLink v-if="dining.parent" :to="parentLink">{{ dining.parent.name }}</AppLink
            ><span v-else>所属食堂待补充</span> ·
            {{ dining.floor || '楼层待补充' }}
          </p>
          <p v-if="isWindow && dining.description" class="detail-affiliation">
            {{ dining.description }}
          </p>
          <div
            v-if="!isCanteen && (!isWindow || (restaurant.price !== null && restaurant.price > 0))"
            class="detail-service-tags"
          >
            <span v-if="!isWindow" class="category-tag">{{ restaurant.category }}</span>
            <span v-if="!isWindow && diningMethods" class="service-tag">{{ diningMethods }}</span>
            <span v-if="restaurant.price !== null && restaurant.price > 0" class="hero-price"
              >{{ priceLabel(restaurant) }} / 人</span
            >
          </div>
          <div class="detail-subtitle">
            <span class="hero-score" :class="{ unrated: displayScore === '—' }">
              <strong v-if="displayScore !== '—'">{{ displayScore }}</strong>
              <span v-else>待评级</span>
            </span>
            <span v-if="!isCanteen" class="hero-review-count"
              >{{ restaurant.reviewCount }} 条点评</span
            >
            <span v-if="isCanteen" class="detail-affiliation">{{
              dining.campus || '校园食堂'
            }}</span>
            <button
              v-if="!isCanteen"
              type="button"
              class="score-toggle"
              :aria-expanded="dimensionsExpanded"
              :aria-controls="dimensionsId"
              @click="dimensionsExpanded = !dimensionsExpanded"
            >
              四维评分<svg viewBox="0 0 16 16" aria-hidden="true"><path d="m4 6 4 4 4-4" /></svg>
            </button>
          </div>
          <div
            v-if="!isCanteen"
            v-show="dimensionsExpanded"
            :id="dimensionsId"
            class="score-summary"
          >
            <div class="score-dimensions">
              <span v-for="d in ratingDimensions" :key="d.key"
                >{{ d.label }}
                <strong>{{
                  restaurant.dimensions[d.key] === null
                    ? '待评级'
                    : (restaurant.dimensions[d.key]! / 2).toFixed(1)
                }}</strong></span
              >
            </div>
          </div>
          <div class="detail-location-actions">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M19 10c0 5-7 11-7 11S5 15 5 10a7 7 0 0 1 14 0Z" />
              <circle cx="12" cy="10" r="2.3" />
            </svg>
            <span class="detail-location">{{ restaurant.address || '地址待补充' }}</span>
            <ResourceLink
              v-if="navigation"
              class="detail-navigation"
              :href="navigation"
              target="_blank"
              rel="noopener noreferrer"
              aria-label="在高德中打开导航"
            >
              导航<svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="m3 10 18-7-7 18-3-8Z" />
              </svg>
            </ResourceLink>
          </div>
          <!-- 主操作与次要入口统一排列，收藏回执仍保留在原控件内。 -->
          <div class="detail-primary-actions">
            <ReviewComposer
              v-if="!restaurant.closed"
              :restaurant="restaurant"
              fixed-target
              :trigger-label="isCanteen ? '＋ 评窗口' : isWindow ? '＋ 评此窗口' : '＋ 写点评'"
              @published="published"
            />
            <AppLink
              v-if="!isCanteen && !isWindow"
              class="correction-entry"
              :to="`/restaurants/${encodeURIComponent(restaurant.id)}/correction`"
              >补充 / 纠错</AppLink
            >
          </div>
          <div v-if="!isCanteen && publicTags.length" class="detail-tags">
            <span v-for="tag in publicTags.slice(0, 4)" :key="tag">{{ tag }}</span>
          </div>
          <p v-if="restaurant.closed" class="closed-note">暂停营业 · 历史点评仍可阅读</p>
          <p v-if="statisticsLoading" class="panel-note" role="status">正在更新评分…</p>
          <p v-if="statisticsError" class="panel-note" role="alert">
            {{ statisticsError }}
            <button type="button" @click="refreshStatistics">重试刷新</button>
          </p>
        </div>
      </header>

      <CanteenWindows
        v-if="isCanteen"
        :key="restaurant.id"
        :canteen-id="restaurant.id"
        :windows="dining.windows"
      />

      <section
        v-if="!isCanteen && dining.dishes.length"
        class="detail-dishes"
        aria-labelledby="dishes-title"
      >
        <h2 id="dishes-title">
          {{ isWindow ? '窗口菜品与价格' : '菜品信息' }}
        </h2>
        <div v-if="dining.dishes.length" class="dish-list">
          <article v-for="dish in dining.dishes" :key="dish.id" class="dish-row">
            <img
              v-if="dish.imageUrl && !failedDishes.includes(dish.id)"
              :src="dish.imageUrl"
              :alt="dish.name"
              loading="lazy"
              @error="failedDishes.push(dish.id)"
            />
            <div>
              <h3>{{ dish.name }}</h3>
              <p v-if="dish.description">{{ dish.description }}</p>
            </div>
            <strong v-if="dish.price !== null && dish.price !== undefined"
              >¥{{ dish.price }}</strong
            >
          </article>
        </div>
        <p v-else class="dining-empty">暂无菜品信息</p>
      </section>

      <section
        v-if="!isCanteen"
        id="student-reviews"
        class="detail-reviews"
        :aria-label="isWindow ? '窗口点评' : '餐厅点评'"
      >
        <ReviewPanel
          :key="restaurant.id"
          :restaurant-id="restaurant.id"
          :window="isWindow"
          :revision="reviewRevision"
          star-scale
          :title="isWindow ? '本窗口点评' : '同学点评'"
          @changed="refreshStatistics"
        />
      </section>
      <footer class="detail-extra">
        <RestaurantManagementActions v-if="!isWindow" :id="restaurant.id" :key="restaurant.id" />
      </footer>
    </article>
    <section v-else class="missing-restaurant" aria-labelledby="missing-title">
      <h1 id="missing-title">没有找到这家店。</h1>
      <p>餐厅可能已删除或链接有误，回去看看其他好味道吧。</p>
      <AppLink class="primary-action" :to="backTarget" @click.capture="returnToSource"
        >返回</AppLink
      >
    </section>
  </div>
</template>

<style scoped src="./restaurant-detail.css"></style>
