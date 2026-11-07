<script setup lang="ts">
import axios from 'axios'
import { ReviewComposer, ReviewPanel } from '@/modules/review'
import RestaurantAlbum from './RestaurantAlbum.vue'
import FavoriteButton from './FavoriteButton.vue'
import { navigationUrl } from '@/modules/map'
import { computed, onUnmounted, ref, watch } from 'vue'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { fetchRestaurant } from '../api/restaurantApi'
import type { RestaurantReadModel } from '../model/restaurant.types'
import { priceLabel, ratingDimensions } from '../model/catalog.logic'
import StarScore from '@/shared/ui/StarScore.vue'
import { useRoute } from 'vue-router'
import { diningDetails, canteenRating } from '../model/campusDining'
import CanteenWindows from './CanteenWindows.vue'
import RestaurantManagementActions from './RestaurantManagementActions.vue'
import { useRestaurantCatalog } from '../model/catalog.store'
import { isProvenanceTag } from './restaurantPresentation'

const props = defineProps<{ id: string }>()
const restaurant = ref<RestaurantReadModel | null>(null)
const dining = computed(() => (restaurant.value ? diningDetails(restaurant.value) : null))
const isCanteen = computed(() => dining.value?.kind === 'canteen')
const isWindow = computed(() => dining.value?.kind === 'window')
const navigation = computed(() => (restaurant.value ? navigationUrl(restaurant.value) : null))
const failedDishes = ref<string[]>([])
// 食堂首屏只逛窗口；旧整体点评按需读取，不参与窗口汇总。
const legacyReviews = ref(false)
const route = useRoute()
const aggregate = computed(() =>
  canteenRating(restaurant.value?.id ?? props.id, dining.value?.windows ?? null),
)
const displayScore = computed(() =>
  isCanteen.value ? aggregate.value.score : (restaurant.value?.score ?? null),
)
/** 返回链接只使用真实父食堂ID；筛选来自窗口入口快照，直达窗口时回到其所属楼层。 */
const parentLink = computed(() => ({
  path: `/restaurants/${encodeURIComponent(dining.value?.parent?.id ?? '')}`,
  query: {
    floor:
      typeof route.query.fromFloor === 'string'
        ? route.query.fromFloor
        : (dining.value?.floor ?? ''),
    windowCategory: typeof route.query.fromCategory === 'string' ? route.query.fromCategory : '',
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
const copyFeedback = ref<string | null>(null)
const manualCopyText = ref('')
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
    const fresh = await fetchRestaurant(id, signal)
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
  copyFeedback.value = null
  manualCopyText.value = ''
  failedDishes.value = []
  legacyReviews.value = false
  try {
    const result = await fetchRestaurant(props.id, signal)
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
  () => props.id,
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

/** 剪贴板权限或HTTP环境受限时提供可选中文本，不把未复制的操作宣告为成功。 */
async function copyText(text: string): Promise<void> {
  const requestedId = props.id
  copyFeedback.value = null
  manualCopyText.value = ''
  try {
    if (!globalThis.navigator.clipboard?.writeText) throw new Error('Clipboard unavailable')
    await globalThis.navigator.clipboard.writeText(text)
    if (props.id !== requestedId) return
    copyFeedback.value = '已复制，可以分享给同学了。'
  } catch {
    if (props.id !== requestedId) return
    manualCopyText.value = text
    copyFeedback.value = '暂时无法自动复制，请选中下方文字手动复制。'
  }
}

/** 分享仅包含当前餐厅页面，不拼接浏览器存储中的登录凭证。 */
function shareRestaurant(): void {
  if (!restaurant.value) return
  const link = new globalThis.URL(
    `/restaurants/${encodeURIComponent(restaurant.value.id)}`,
    globalThis.location.origin,
  )
  void copyText(
    `${restaurant.value.name}\n${restaurant.value.address}\n${link.href}\n营业与消费信息请出发前核实。`,
  )
}
</script>

<template>
  <section v-if="loading" class="missing-restaurant" role="status">正在加载餐厅资料…</section>
  <section v-else-if="error" class="missing-restaurant" role="alert">
    <h1>餐厅资料暂时无法加载。</h1>
    <p>{{ error }}</p>
    <button type="button" class="primary-action" @click="loadDetail">重新加载</button>
    <RouterLink to="/">返回首页</RouterLink>
  </section>
  <article
    v-else-if="restaurant && dining"
    class="restaurant-detail"
    :class="`detail-${dining.kind}`"
    aria-labelledby="restaurant-title"
  >
    <nav class="detail-breadcrumb" aria-label="详情导航">
      <RouterLink v-if="isWindow && dining.parent" :to="parentLink"
        >← 返回 {{ dining.parent.name
        }}<template v-if="dining.floor">（{{ dining.floor }}）</template></RouterLink
      >
      <RouterLink v-else to="/">← 返回首页</RouterLink>
      <span>{{ isCanteen ? '校园食堂' : isWindow ? '食堂窗口' : restaurant.category }}</span>
    </nav>

    <header class="detail-hero" :class="{ 'canteen-hero': isCanteen }">
      <RestaurantAlbum
        :key="restaurant.id"
        :restaurant-id="restaurant.id"
        :name="restaurant.name"
      />
      <div class="detail-heading">
        <div class="hero-topline">
          <h1 id="restaurant-title">{{ restaurant.name }}</h1>
          <ReviewComposer
            :restaurant="restaurant"
            fixed-target
            :trigger-label="isCanteen ? '＋ 评窗口' : isWindow ? '＋ 评此窗口' : '＋ 写点评'"
            @published="published"
          />
        </div>
        <p v-if="isWindow" class="detail-affiliation">
          <RouterLink v-if="dining.parent" :to="parentLink">{{ dining.parent.name }}</RouterLink
          ><span v-else>所属食堂待补充</span> · {{ dining.floor || '楼层待补充' }} ·
          {{ restaurant.category }}
        </p>
        <div class="detail-subtitle">
          <StarScore class="hero-score" :score="displayScore" />
          <span v-if="isCanteen" class="aggregate-source">{{
            dining.windows === null
              ? '窗口评分待接入'
              : aggregate.count
                ? `汇总自 ${aggregate.count} 个已评窗口`
                : '暂无已评窗口'
          }}</span>
          <span v-else>{{ restaurant.reviewCount }} 条点评</span>
          <span v-if="!isCanteen" class="hero-price"
            >{{ priceLabel(restaurant) }}<small v-if="restaurant.price !== null"> / 人</small></span
          >
          <span v-if="isCanteen" class="detail-affiliation">{{ dining.campus || '校园食堂' }}</span>
        </div>
        <div v-if="!isCanteen" class="score-summary">
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
          <span
            v-if="restaurant.participantCount > 0 && restaurant.participantCount < 5"
            class="panel-note"
            >{{ restaurant.participantCount }} 人评分 · 样本较少</span
          >
        </div>
        <div class="detail-location-actions">
          <span v-if="!isWindow && restaurant.supportsDineIn === true" class="service-tag"
            >支持堂食</span
          ><span v-if="!isWindow && restaurant.supportsDelivery === true" class="service-tag"
            >支持外卖</span
          >
          <span v-if="!isWindow && !isCanteen">{{ restaurant.category }}</span>
          <span class="detail-location">{{ restaurant.address || '地址待补充' }}</span>
        </div>
        <RouterLink
          v-if="!isWindow"
          class="correction-entry"
          :to="`/restaurants/${encodeURIComponent(restaurant.id)}/correction`"
          >✎ 补充 / 纠错</RouterLink
        >
        <FavoriteButton v-if="!isWindow" :restaurant-id="restaurant.id" />
        <div v-if="!isCanteen && publicTags.length" class="detail-tags">
          <span v-for="tag in publicTags.slice(0, 4)" :key="tag">{{ tag }}</span>
        </div>
        <p v-if="restaurant.closed" class="closed-note">暂停营业 · 历史点评仍可阅读</p>
        <p v-if="statisticsLoading" class="panel-note" role="status">正在更新评分…</p>
        <p v-if="statisticsError" class="panel-note" role="alert">
          {{ statisticsError }} <button type="button" @click="refreshStatistics">重试刷新</button>
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
      v-if="!isCanteen && (isWindow || dining.dishes.length)"
      class="detail-dishes"
      aria-labelledby="dishes-title"
    >
      <h2 id="dishes-title">{{ isWindow ? '窗口菜品与价格' : '菜品信息' }}</h2>
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
          <strong v-if="dish.price !== null && dish.price !== undefined">¥{{ dish.price }}</strong>
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
        :revision="reviewRevision"
        star-scale
        :title="isWindow ? '本窗口点评' : '同学点评'"
        @changed="refreshStatistics"
      />
    </section>
    <details
      v-else
      class="legacy-canteen-reviews"
      @toggle="($event.target as HTMLDetailsElement).open && (legacyReviews = true)"
    >
      <summary>
        历史食堂点评 <small>{{ restaurant.reviewCount }}</small>
      </summary>
      <p class="panel-note">此前对食堂整体的点评，不参与窗口汇总。</p>
      <ReviewPanel
        v-if="legacyReviews"
        :key="restaurant.id"
        :restaurant-id="restaurant.id"
        :revision="reviewRevision"
        star-scale
        title="历史食堂点评"
        @changed="refreshStatistics"
      />
    </details>

    <footer class="detail-extra">
      <div class="location-links">
        <button
          type="button"
          class="copy-address"
          @click="copyText(restaurant.address || restaurant.name)"
        >
          复制地址
        </button>
        <button type="button" class="share-button" @click="shareRestaurant">分享 ↗</button>
        <a v-if="navigation" :href="navigation" target="_blank" rel="noopener noreferrer"
          >在高德中打开 ↗</a
        >
      </div>
      <p v-if="copyFeedback" class="copy-feedback" role="status">{{ copyFeedback }}</p>
      <label v-if="manualCopyText" class="manual-copy"
        >待复制的内容<textarea
          :value="manualCopyText"
          readonly
          rows="4"
          @focus="($event.target as HTMLTextAreaElement).select()"
        />
      </label>
      <RestaurantManagementActions :id="restaurant.id" :key="restaurant.id" />
    </footer>
  </article>
  <section v-else class="missing-restaurant" aria-labelledby="missing-title">
    <h1 id="missing-title">没有找到这家店。</h1>
    <p>餐厅可能已删除或链接有误，回去看看其他好味道吧。</p>
    <RouterLink class="primary-action" to="/">返回餐厅目录</RouterLink>
  </section>
</template>

<style scoped src="./restaurant-detail.css"></style>
