<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  fetchRestaurantRanking,
  type RankingCampus,
  type RankingLoader,
  type RankingPeriod,
  type RestaurantRanking,
} from '../api/restaurantRankingApi'

// 正式页面始终使用真实接口；仅开发预览页可以通过独立loader提供明确标注的示例。
const props = defineProps<{ loader?: RankingLoader }>()
const route = useRoute()
const router = useRouter()
const campus = computed<RankingCampus>(() =>
  route.query.campusCode === 'SHAHE' ? 'SHAHE' : 'QINGSHUIHE',
)
const period = computed<RankingPeriod>(() =>
  route.query.periodType === 'MONTH' ? 'MONTH' : 'WEEK',
)
const days = computed(() => (period.value === 'WEEK' ? 7 : 30))
const ranking = ref<RestaurantRanking | null>(null)
const loading = ref(true)
const failed = ref(false)
const failedPhotos = ref(new Set<string>())
let request = 0
let controller: InstanceType<typeof globalThis.AbortController> | undefined

const updatedAt = computed(() =>
  ranking.value?.generatedAt
    ? new Intl.DateTimeFormat('zh-CN', {
        month: 'long',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
        timeZone: 'Asia/Shanghai',
      }).format(new Date(ranking.value.generatedAt))
    : '',
)

/** 切换即清除旧榜并取消请求，代次校验同时防御不响应取消的迟到结果。 */
async function load() {
  controller?.abort()
  controller = new globalThis.AbortController()
  const signal = controller.signal
  const ticket = ++request
  loading.value = true
  failed.value = false
  ranking.value = null
  failedPhotos.value = new Set()
  try {
    const result = await (props.loader ?? fetchRestaurantRanking)(
      campus.value,
      period.value,
      signal,
    )
    if (signal.aborted || ticket !== request) return
    ranking.value = result
  } catch {
    if (!signal.aborted && ticket === request) failed.value = true
  } finally {
    if (!signal.aborted && ticket === request) loading.value = false
  }
}

/** URL保存选择，刷新、分享地址和浏览器前进/后退均恢复同一榜单；不在前端重新排名。 */
function select(campusCode: RankingCampus, periodType: RankingPeriod) {
  void router.push({ query: { ...route.query, campusCode, periodType } })
}

watch([campus, period], () => void load(), { immediate: true })
onScopeDispose(() => {
  request++
  controller?.abort()
})
</script>

<template>
  <section class="ranking-page" aria-labelledby="ranking-title">
    <RouterLink class="ranking-back" to="/">
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="m14 6-6 6 6 6" />
      </svg>
      返回首页
    </RouterLink>
    <div class="ranking-heading">
      <h1 id="ranking-title">近期推荐榜</h1>
      <div class="ranking-campuses" role="group" aria-label="选择校区">
        <button
          type="button"
          :aria-pressed="campus === 'QINGSHUIHE'"
          @click="select('QINGSHUIHE', period)"
        >
          清水河
        </button>
        <button type="button" :aria-pressed="campus === 'SHAHE'" @click="select('SHAHE', period)">
          沙河
        </button>
      </div>
    </div>
    <div class="ranking-controls">
      <div class="ranking-periods" role="group" aria-label="选择周期">
        <button type="button" :aria-pressed="period === 'WEEK'" @click="select(campus, 'WEEK')">
          近7天
        </button>
        <button type="button" :aria-pressed="period === 'MONTH'" @click="select(campus, 'MONTH')">
          近30天
        </button>
      </div>
      <time
        v-if="updatedAt && ranking?.status !== 'UNAVAILABLE'"
        class="ranking-updated"
        :datetime="ranking?.generatedAt ?? undefined"
        >{{ updatedAt }} 更新</time
      >
    </div>
    <p class="ranking-explanation">
      按本周期四维评分与评价人数计算推荐分，每小时更新；首页综合分参考全部历史评价。
    </p>
    <div class="ranking-results" :aria-busy="loading" aria-live="polite">
      <div v-if="loading" class="ranking-state" role="status">正在加载榜单…</div>
      <div v-else-if="failed" class="ranking-state" role="alert">
        <h2>榜单暂时无法加载</h2>
        <button type="button" @click="load">重新加载</button>
      </div>
      <div v-else-if="ranking?.status === 'PREPARING'" class="ranking-state" role="status">
        <h2>榜单准备中</h2>
        <p>稍后再来看看</p>
        <button type="button" @click="load">重新加载</button>
      </div>
      <div v-else-if="ranking?.status === 'UNAVAILABLE'" class="ranking-state" role="status">
        <h2>榜单暂时不可用</h2>
        <button type="button" @click="load">重新加载</button>
      </div>
      <div v-else-if="!ranking?.items.length" class="ranking-state" role="status">
        <h2>还没有餐厅上榜</h2>
        <RouterLink to="/">去看看附近的餐厅 →</RouterLink>
      </div>
      <template v-else>
        <ol class="ranking-list" aria-label="餐厅排名">
          <li v-for="item in ranking.items" :key="item.restaurantId">
            <RouterLink
              class="ranking-row"
              :to="`/restaurants/${encodeURIComponent(item.restaurantId)}`"
              :aria-label="`第${item.rank}名，${item.name}，推荐分${item.recommendationScore.toFixed(1)}分，${item.participantCount}人评价`"
            >
              <span
                class="ranking-number"
                :class="{ top: item.rank <= 3, first: item.rank === 1 }"
                aria-hidden="true"
                >{{ item.rank }}</span
              >
              <div class="ranking-cover">
                <img
                  v-if="item.coverUrl && !failedPhotos.has(item.restaurantId)"
                  :src="item.coverUrl"
                  :alt="`${item.name}的封面`"
                  loading="lazy"
                  decoding="async"
                  @error="failedPhotos.add(item.restaurantId)"
                />
                <div v-else class="ranking-placeholder">
                  <svg viewBox="0 0 64 64" fill="none" aria-hidden="true">
                    <path
                      d="M12 28h40c-2 17-9 22-20 22S14 45 12 28Zm7 27h26M25 10v10m9-12v12m9-10v10"
                    />
                  </svg>
                  <small>{{ item.coverUrl ? '图片暂不可用' : '暂无配图' }}</small>
                </div>
              </div>
              <div class="ranking-info">
                <h2 class="ranking-name">{{ item.name }}</h2>
                <span class="ranking-mobile-score" aria-hidden="true"
                  ><strong>{{ item.recommendationScore.toFixed(1) }}</strong
                  ><small>推荐分</small></span
                >
                <span class="ranking-count"
                  >近{{ days }}天 · {{ item.participantCount }}人评价</span
                >
              </div>
              <div class="ranking-score" aria-hidden="true">
                <strong>{{ item.recommendationScore.toFixed(1) }}</strong
                ><small>近期推荐分</small>
              </div>
              <svg class="ranking-chevron" viewBox="0 0 24 24" aria-hidden="true">
                <path d="m9 6 6 6-6 6" />
              </svg>
            </RouterLink>
          </li>
        </ol>
        <p class="ranking-end">已展示全部上榜餐厅</p>
      </template>
    </div>
  </section>
</template>

<style scoped src="./restaurant-rankings.css"></style>

<style scoped>
.ranking-explanation {
  color: #71717a;
  font-size: 12px;
  line-height: 1.7;
  margin: 0 0 20px;
}
</style>
