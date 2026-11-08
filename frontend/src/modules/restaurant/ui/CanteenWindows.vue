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
// 楼层作为可展开的浏览单元；未知楼层保留独立分组，不能擅自分配到一楼。
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
        食堂窗口
        <small v-if="windows !== null">已收录 {{ records.length }} 个</small>
      </h2>
    </div>
    <div class="window-filters" role="group" aria-label="按楼层筛选窗口">
      <span class="filter-label">楼层</span>
      <button type="button" :aria-pressed="!floor" @click="floor = ''">
        全部楼层<small v-if="windows !== null">({{ records.length }})</small>
      </button>
      <button
        v-for="value in floors"
        :key="value"
        type="button"
        :aria-pressed="floor === value"
        @click="floor = value"
      >
        {{ value }}<small>({{ records.filter((w) => w.floor === value).length }})</small>
      </button>
    </div>
    <template v-if="records.length">
      <details v-for="group in groups" :key="`${floor}:${group.name}`" class="floor-section" open>
        <summary>
          <strong>{{ group.name }}</strong
          ><span>{{ group.windows.length }} 个窗口</span
          ><span class="floor-toggle">展开 / 收起</span>
        </summary>
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
                <span>{{ window.floor || '楼层待补充' }}</span
                ><span class="enter-window">进窗口 →</span>
              </p>
              <p v-if="window.closed" class="excluded-note">不计入当前汇总</p>
            </div>
          </RouterLink>
        </div>
      </details>
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
.filter-label {
  align-self: center;
  font-size: 12px;
  color: #929ba5;
  flex-shrink: 0;
}
.window-filters small {
  margin-left: 4px;
  font-size: 11px;
  font-weight: 400;
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
.floor-section > summary::before {
  content: '›';
  color: #9aa3ad;
  font-size: 18px;
}
.floor-section[open] > summary::before {
  content: '⌄';
}
.canteen-windows {
  padding: 28px 0;
  border-bottom: 1px solid #edf0f2;
}
.section-heading {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
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
  margin-left: 10px;
}
a {
  color: inherit;
  text-decoration: none;
}
.floor-section {
  border-top: 1px solid #edf0f2;
  margin-top: 18px;
}
.floor-section > summary {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px 0 0;
  cursor: pointer;
  list-style: none;
}
.floor-section > summary strong {
  font-size: 19px;
}
.floor-section > summary span {
  font-size: 12px;
  color: #687583;
}
.floor-toggle {
  margin-left: auto;
}
.window-filters {
  display: flex;
  overflow-x: auto;
  gap: 22px;
  margin-top: 20px;
}
.window-filters button {
  border: 0;
  background: none;
  color: #626e7b;
  padding: 8px 0;
  white-space: nowrap;
  min-height: 44px;
  font: inherit;
  font-size: 14px;
  cursor: pointer;
  border-bottom: 2px solid transparent;
}
.window-filters button[aria-pressed='true'] {
  color: #a45e0c;
  border-bottom-color: #e99a22;
}
.filter-result {
  color: #687583;
  font-size: 12px;
  margin: 12px 0 0;
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
a:focus-visible,
summary:focus-visible {
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
</style>
