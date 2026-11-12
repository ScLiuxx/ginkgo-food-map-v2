<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useUserLocation } from '@/modules/map/model/userLocation.store'
import { useRestaurantCatalog } from '../model/catalog.store'
import { campusOrigins, distanceOriginLabel } from '../model/distanceOrigin'
import type { CatalogFilters, RestaurantSort } from '../model/restaurant.types'

/** 排序与距离参照分开；只有主动选择距我或更新位置才调用定位服务。 */
const props = withDefaults(defineProps<{ filters?: CatalogFilters }>(), {
  filters: undefined,
})
const catalog = useRestaurantCatalog()
const filters = computed(() => props.filters ?? catalog.filters)
const location = useUserLocation()
const root = ref<globalThis.HTMLElement>()
const opened = ref(false)
const selectingMe = ref(false)
const notice = ref('')
const relevanceDefault = computed(() => {
  const keyword = filters.value.keyword.trim()
  return Boolean(keyword) && !/^\p{Script=Han}$/u.test(keyword)
})
const distanceText = computed(() =>
  selectingMe.value
    ? '定位中…'
    : filters.value.sort === 'distance'
      ? `距${distanceOriginLabel(filters.value.distanceOrigin)}最近`
      : '距离最近',
)

function chooseSort(sort: RestaurantSort) {
  selectingMe.value = false
  opened.value = false
  notice.value = ''
  filters.value.sort = sort
}

/** 批量变更参照点和排序，保留筛选条件，目录统一回第一页查询。 */
function chooseCampus(campus: keyof typeof campusOrigins) {
  selectingMe.value = false
  notice.value = ''
  opened.value = false
  const point = campusOrigins[campus]
  Object.assign(filters.value, {
    distanceOrigin: campus,
    latitude: point.latitude,
    longitude: point.longitude,
    sort: 'distance',
  })
}

function applyMyLocation() {
  if (!location.location) return
  selectingMe.value = false
  notice.value = ''
  Object.assign(filters.value, {
    distanceOrigin: 'USER',
    longitude: location.location.center[0],
    latitude: location.location.center[1],
    sort: 'distance',
  })
}

/** 定位成功前保留原列表；拒绝授权或失败不能把列表清空或冒充距离排序。 */
async function chooseMe(refresh = false) {
  opened.value = false
  notice.value = ''
  if (!refresh && location.location) {
    applyMyLocation()
    return
  }
  selectingMe.value = true
  await location.locate()
}

// 切到其他排序或校区后，迟到回调不能重新选中距我。
watch(
  () => location.location,
  (position) => {
    if (selectingMe.value && position) applyMyLocation()
  },
)
watch(
  () => [location.preparing, location.locating, location.message] as const,
  () => {
    if (
      selectingMe.value &&
      !location.preparing &&
      !location.locating &&
      !location.location &&
      location.message
    ) {
      selectingMe.value = false
      notice.value = '无法获取位置，可选择校区距离。' + location.message
    }
  },
)
function outside(event: globalThis.PointerEvent) {
  if (event.target instanceof globalThis.Node && !root.value?.contains(event.target))
    opened.value = false
}
onMounted(() => globalThis.document.addEventListener('pointerdown', outside))
onUnmounted(() => {
  selectingMe.value = false
  globalThis.document.removeEventListener('pointerdown', outside)
})
</script>

<template>
  <div ref="root" class="nearby-location" @keydown.esc="opened = false">
    <div class="sort-pills" role="group" aria-label="餐厅排序">
      <button
        type="button"
        :aria-pressed="
          filters.sort === 'auto' ||
          filters.sort === 'relevance' ||
          (!filters.keyword.trim() && filters.sort === 'score')
        "
        @click="chooseSort('auto')"
      >
        {{ relevanceDefault ? '相关度' : '综合排序' }}
      </button>
      <button
        type="button"
        :aria-pressed="filters.sort === 'reviews' || filters.sort === 'recommend'"
        @click="chooseSort('reviews')"
      >
        热度优先
      </button>
      <div class="distance-picker">
        <button
          type="button"
          :aria-pressed="filters.sort === 'distance'"
          :aria-expanded="opened"
          aria-haspopup="listbox"
          aria-label="选择距离排序"
          @click="opened = !opened"
        >
          {{ distanceText }} <span aria-hidden="true">▾</span>
        </button>
        <div v-if="opened" class="distance-menu" role="listbox" aria-label="距离参照点">
          <p>距离参照点 · 直线距离</p>
          <button
            v-for="(point, key) in campusOrigins"
            :key="key"
            type="button"
            role="option"
            :aria-selected="filters.sort === 'distance' && filters.distanceOrigin === key"
            @click="chooseCampus(key)"
          >
            距{{ point.label }}最近
          </button>
          <button
            type="button"
            role="option"
            :aria-selected="
              filters.sort === 'distance' &&
              (!filters.distanceOrigin || filters.distanceOrigin === 'USER')
            "
            @click="chooseMe()"
          >
            距我最近 <small>需要定位</small>
          </button>
          <button
            v-if="location.location"
            type="button"
            class="refresh-location"
            @click="chooseMe(true)"
          >
            更新我的位置
          </button>
        </div>
      </div>
      <button
        v-if="relevanceDefault"
        type="button"
        :aria-pressed="filters.sort === 'score'"
        @click="chooseSort('score')"
      >
        综合评分
      </button>
    </div>
    <p v-if="notice" class="location-notice" role="status">{{ notice }}</p>
  </div>
</template>

<style scoped>
.nearby-location {
  position: relative;
  min-width: 0;
  font-size: 12px;
  color: #777;
}
.sort-pills {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
button {
  white-space: nowrap;
  font: inherit;
  font-size: 14px;
  border: 1px solid transparent;
  border-radius: 24px;
  padding: 10px 18px;
  min-height: 44px;
  background: #f2f3f5;
  color: #363a3e;
  cursor: pointer;
}
button[aria-pressed='true'],
button[aria-selected='true'] {
  border-color: #d8a969;
  color: #a75c15;
  background: #fff8ed;
  font-weight: 650;
}
button:focus-visible {
  outline: 2px solid #ad6f26;
  outline-offset: 3px;
}
.distance-picker {
  position: relative;
}
.distance-menu {
  position: absolute;
  z-index: 30;
  top: calc(100% + 8px);
  right: 0;
  width: 230px;
  max-width: calc(100vw - 24px);
  padding: 8px;
  border: 1px solid #e9e2d8;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 8px 28px #30201020;
}
.distance-menu p {
  padding: 6px 10px;
  margin: 0;
  font-size: 12px;
  color: #747474;
}
.distance-menu button {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  text-align: left;
  border-radius: 7px;
  background: #fff;
}
.distance-menu button:hover {
  background: #fff8ed;
}
.distance-menu small {
  color: #767676;
  font-size: 11px;
}
.distance-menu .refresh-location {
  border-top: 1px solid #eee;
  border-radius: 0;
  color: #925c22;
  font-size: 12px;
}
.location-notice {
  max-width: min(480px, 90vw);
  margin: 6px 0 0;
  line-height: 1.6;
}
@media (max-width: 700px) {
  .sort-pills {
    gap: 6px;
  }
  .sort-pills > button,
  .distance-picker > button {
    padding: 8px 11px;
    font-size: 12px;
  }
}
</style>
