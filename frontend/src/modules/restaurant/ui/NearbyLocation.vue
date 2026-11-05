<script setup lang="ts">
import { computed } from 'vue'
import { useUserLocation } from '@/modules/map/model/userLocation.store'
import { useRestaurantCatalog } from '../model/catalog.store'
import type { CatalogFilters } from '../model/restaurant.types'
/** 排序仅在用户点击时变化，距离按钮才触发一次定位授权。 */
const props = withDefaults(defineProps<{ filters?: CatalogFilters }>(), { filters: undefined })
const catalog = useRestaurantCatalog()
const filters = computed(() => props.filters ?? catalog.filters)
// 单个汉字使用ES字符包含搜索，保留综合分默认值，不将其标为相关度。
const relevanceDefault = computed(() => {
  const keyword = filters.value.keyword.trim()
  return Boolean(keyword) && !/^\p{Script=Han}$/u.test(keyword)
})
const location = useUserLocation()

/** 点击即选择排序；已有位置直接排序，不让重新定位把选中态重置。 */
function nearest() {
  filters.value.sort = 'distance'
  if (location.location) {
    filters.value.longitude = location.location.center[0]
    filters.value.latitude = location.location.center[1]
  } else {
    void location.locate()
  }
}
</script>
<template>
  <div class="nearby-location">
    <div class="sort-pills" role="group" aria-label="餐厅排序">
      <button
        type="button"
        :aria-pressed="filters.sort === 'auto' || filters.sort === 'relevance' || (!filters.keyword.trim() && filters.sort === 'score')"
        @click="filters.sort = 'auto'"
      >
        {{ relevanceDefault ? '相关度' : '综合排序' }}
      </button>
      <button type="button" :aria-pressed="filters.sort === 'distance'" @click="nearest">
        {{ location.preparing || location.locating ? '定位中…' : '距离最近' }}
      </button>
      <button
        type="button"
        :aria-pressed="filters.sort === 'recommend'"
        @click="filters.sort = 'recommend'"
      >
        好评优先
      </button>
      <button
        v-if="relevanceDefault"
        type="button"
        :aria-pressed="filters.sort === 'score'"
        @click="filters.sort = 'score'"
      >综合评分</button>
    </div>
    <p v-if="location.message && !location.location" role="status">{{ location.message }}</p>
  </div>
</template>
<style scoped>
.nearby-location {
  position: relative;
  font-size: 12px;
  color: #777;
}
.sort-pills {
  display: flex;
  gap: 10px;
}
button {
  white-space: nowrap;
  font: inherit;
  font-size: 14px;
  border: 1px solid transparent;
  border-radius: 24px;
  padding: 9px 18px;
  background: #f2f3f5;
  color: #363a3e;
  cursor: pointer;
}
button[aria-pressed='true'] {
  border-color: #d8a969;
  color: #c47a25;
  background: #fff8ed;
  font-weight: 650;
}
button:focus-visible {
  outline: 2px solid #ad6f26;
  outline-offset: 3px;
}
p {
  position: static;
  width: min(440px, 80vw);
  padding: 4px 0;
  margin: 5px 0;
  background: white;
}
</style>
