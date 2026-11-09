<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import StarScore from '@/shared/ui/StarScore.vue'
import type { DiningWindow } from '../model/campusDining'
import { canteenWindows, windowHasRating, compareFloors } from '../model/campusDining'

const props = defineProps<{
  canteenId: string
  windows: DiningWindow[] | null
}>()
const route = useRoute(),
  router = useRouter()
const floor = ref(typeof route.query.floor === 'string' ? route.query.floor : '')
/** 筛选留在当前历史条目的URL，窗口链接携带同一快照；浏览器返回、刷新和返回食堂均可恢复。 */
watch(floor, () => {
  void router.replace({
    path: route.path,
    query: {
      ...route.query,
      floor: floor.value || undefined,
      windowCategory: undefined,
    },
    hash: route.hash,
  })
})
watch(
  () => route.query.floor,
  () => {
    floor.value =
      typeof route.query.floor === 'string' && floors.value.includes(route.query.floor)
        ? route.query.floor
        : ''
  },
)
/** 清空楼层条件，不触发路由跳转或重新加载整页。 */
function resetFilters(): void {
  floor.value = ''
}
const failedImages = ref<string[]>([])
const records = computed(() => canteenWindows(props.canteenId, props.windows))
// 选项来自完整真实集合，组合过滤不会使其他楼层入口消失；未知楼层依然可在“全部”找到。
const floors = computed(() =>
  [...new Set(records.value.map((w) => w.floor).filter((v): v is string => !!v))].sort(
    compareFloors,
  ),
)
const visible = computed(() => records.value.filter((w) => !floor.value || w.floor === floor.value))
// 只有多个实际楼层时展示切换；单楼层只在标题旁标注一次，避免重复楼层说明。
const multipleFloors = computed(() => new Set(records.value.map((window) => window.floor)).size > 1)
// 查看全部时按楼层归组；已选楼层不再重复标题，未知楼层不擅自分配到一楼。
const groups = computed(() => {
  const names = [...new Set(visible.value.map((window) => window.floor))].sort((a, b) =>
    compareFloors(a || '楼层待补充', b || '楼层待补充'),
  )
  return names.map((name) => ({
    name: name || '楼层待补充',
    windows: visible.value.filter((window) => window.floor === name),
  }))
})
watch(
  () => props.canteenId,
  () => {
    floor.value = ''
    failedImages.value = []
  },
)
watch(
  floors,
  (values) => {
    if (!values.includes(floor.value)) floor.value = ''
  },
  { immediate: true },
)

/** 菜品价格只来自实际可选字段；旧窗口仅有特色名称时保持原文，不拼造金额。 */
function menuLine(window: DiningWindow) {
  return window.dishes?.length
    ? window.dishes
        .slice(0, 2)
        .map(
          (dish) =>
            `${dish.name}${dish.price !== null && dish.price !== undefined ? ` ¥${dish.price}` : ''}`,
        )
        .join(' · ')
    : window.specialties.join('、') || window.description
}
</script>

<template>
  <section class="canteen-windows" aria-labelledby="windows-title">
    <div class="section-heading">
      <h2 id="windows-title">
        食堂窗口 <small v-if="windows !== null">({{ records.length }})</small>
      </h2>
      <span v-if="!multipleFloors && records.length" class="single-floor">{{
        groups[0]?.name
      }}</span>
      <div v-if="multipleFloors" class="window-filters" role="group" aria-label="按楼层筛选窗口">
        <button type="button" :aria-pressed="!floor" @click="floor = ''">全部</button>
        <button
          v-for="value in floors"
          :key="value"
          type="button"
          :aria-pressed="floor === value"
          @click="floor = value"
        >
          {{ value }}
        </button>
      </div>
    </div>
    <template v-if="records.length">
      <section v-for="group in groups" :key="`${floor}:${group.name}`" class="floor-section">
        <h3 v-if="multipleFloors && !floor" class="floor-heading">{{ group.name }}</h3>
        <div class="window-grid">
          <RouterLink
            v-for="window in group.windows"
            :key="window.id"
            class="window-row"
            :to="{
              path: `/canteen-windows/${encodeURIComponent(window.id)}`,
              query: { fromFloor: floor },
            }"
          >
            <div class="window-media">
              <img
                v-if="window.imageUrl && !failedImages.includes(window.id)"
                :src="window.imageUrl"
                :alt="window.name"
                loading="lazy"
                @error="failedImages.push(window.id)"
              /><span v-else>暂无窗口照片</span>
            </div>
            <div class="window-copy">
              <h3>{{ window.name }} <small v-if="window.closed">暂停营业</small></h3>
              <p class="window-rating">
                <template v-if="windowHasRating(window)"
                  ><small v-if="window.closed">历史</small><StarScore :score="window.score" /><span
                    >{{ window.reviewCount }} 条点评</span
                  ></template
                >
                <span v-else class="unrated">暂无评分</span>
              </p>
              <p v-if="menuLine(window)" class="specialties">
                {{ menuLine(window) }}
              </p>
              <p v-else-if="window.price !== null">参考人均 ¥{{ window.price }}</p>
              <p class="window-meta">
                <span class="enter-window">进窗口 →</span>
              </p>
              <p v-if="window.closed" class="excluded-note">不计入当前汇总</p>
            </div>
          </RouterLink>
        </div>
      </section>
      <div v-if="!visible.length" class="dining-empty" role="status">
        <p>这个楼层暂时没有窗口。</p>
        <button type="button" @click="resetFilters">查看全部窗口</button>
      </div>
    </template>
    <div v-else class="dining-empty">
      <strong>{{ windows === null ? '窗口资料尚未接入' : '暂未收录窗口' }}</strong>
      <p>暂无可浏览的楼层与窗口</p>
    </div>
  </section>
</template>

<style scoped>
.single-floor {
  font-size: 13px;
  color: #87929e;
  margin-left: auto;
}
.window-row:hover .enter-window {
  color: #ec8424;
}
.window-row:hover h3 {
  color: #e68329;
}
.window-copy {
  flex: 1;
}
.window-copy .window-meta {
  display: flex;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
  color: #929ba5;
}
.enter-window {
  margin-left: auto;
  color: #959ea7;
  white-space: nowrap;
}
.window-copy .excluded-note,
.unrated {
  color: #a1a8b0;
  font-size: 11px;
}
/* 整个窗口区与食堂资料使用同款卡片，内部窗口仍只以细线分隔。 */
.canteen-windows {
  margin-top: 22px;
  padding: 26px;
  background: white;
  border: 1px solid #eceef1;
  border-radius: 16px;
}
.section-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}
.section-heading h2 {
  font-family: inherit;
  font-size: 21px;
  margin: 0;
}
h2 small {
  font-size: 13px;
  color: #687583;
  font-weight: 400;
  margin-left: 4px;
}
a {
  color: inherit;
  text-decoration: none;
}
.floor-section {
  margin-top: 16px;
}
.floor-heading {
  font-size: 15px;
  font-weight: 600;
  color: #586676;
  margin: 22px 0 0;
}
.window-filters {
  display: flex;
  overflow-x: auto;
  gap: 6px;
  max-width: 100%;
  margin-left: auto;
}
.window-filters button {
  border: 0;
  background: none;
  color: #7c8794;
  padding: 8px 13px;
  white-space: nowrap;
  min-height: 44px;
  font: inherit;
  font-size: 14px;
  cursor: pointer;
  border-radius: 6px;
}
.window-filters button[aria-pressed='true'] {
  color: #ed8424;
  background: #fff3e9;
}
.window-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 32px;
}
.window-row {
  display: flex;
  gap: 18px;
  padding: 24px 0;
  border-bottom: 1px solid #edf0f2;
  min-width: 0;
}
.window-media {
  flex: 0 0 124px;
  height: 124px;
  background: #f3f4f5;
  border-radius: 6px;
  overflow: hidden;
  display: grid;
  place-items: center;
  font-size: 11px;
  color: #687583;
}
.window-media img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.window-copy {
  min-width: 0;
}
h3 {
  font-size: 18px;
  margin: 0 0 8px;
  overflow-wrap: anywhere;
}
h3 small {
  display: inline-block;
  white-space: nowrap;
  color: #687583;
  font-size: 12px;
  font-weight: 400;
  background: #f1f3f5;
  padding: 3px 6px;
  border-radius: 4px;
}
.window-copy p {
  font-size: 12px;
  color: #626e7b;
  margin: 5px 0;
  overflow-wrap: anywhere;
}
.window-rating {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: baseline;
}
.window-rating :deep(.star-symbols) {
  display: none;
}
.window-rating :deep(.star-score)::before {
  content: '★';
  font-size: 16px;
}
.window-rating :deep(.star-score) {
  font-size: 19px;
  gap: 5px;
}
.window-rating strong {
  font-size: 18px;
  color: #b46b10;
}
.window-copy .specialties {
  color: #485560;
}
.dining-empty {
  padding: 28px 0;
  color: #626e7b;
  font-size: 14px;
}
.dining-empty p {
  line-height: 1.8;
}
.dining-empty button {
  border: 0;
  background: none;
  color: #9d6316;
  cursor: pointer;
  font: inherit;
  min-height: 44px;
}
button:focus-visible,
a:focus-visible {
  outline: 2px solid #ad6f26;
  outline-offset: 4px;
}
@media (max-width: 750px) {
  .window-grid {
    grid-template-columns: 1fr;
  }
  .window-media {
    flex-basis: 100px;
    height: 110px;
  }
  .window-row {
    gap: 16px;
  }
  .window-copy h3 {
    font-size: 17px;
  }
}
@media (max-width: 600px) {
  .canteen-windows {
    padding: 18px;
    border-radius: 12px;
  }
}
</style>
