<script setup lang="ts">
import { computed } from 'vue'
import { useRestaurantCatalog } from '../model/catalog.store'
import RestaurantAdvancedFilters from './RestaurantAdvancedFilters.vue'
import RandomRestaurant from './RandomRestaurant.vue'
import NearbyLocation from './NearbyLocation.vue'

/** 分类、排序与经营状态直接操作同一份目录状态，由store统一重置页码并查询。 */
const catalog = useRestaurantCatalog()
// 食堂属于餐厅类型，不与火锅、面食等美食品类并列；完整分类字典仍供原有业务使用。
const cuisineCategories = computed(() =>
  catalog.categories.filter((category) => category.code !== 'CANTEEN' && category.name !== '食堂'),
)
// 分类、排序和经营状态不生成重复标签；仅高级价格/距离条件展示可移除标签。
</script>

<template>
  <div class="catalog-filters">
    <form class="search-field" role="search" @submit.prevent="catalog.load">
      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <circle cx="10.5" cy="10.5" r="6.5" stroke="currentColor" stroke-width="1.6" />
        <path d="m16 16 4 4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
      </svg>
      <input
        v-model="catalog.filters.keyword"
        aria-label="搜索餐厅"
        placeholder="搜索餐厅、食堂或窗口…"
        maxlength="100"
        type="search"
      />
      <button type="submit" class="search-submit" aria-label="提交搜索">搜索</button>
    </form>
    <nav class="quick-categories" aria-label="美食快捷分类">
      <button
        type="button"
        :aria-pressed="!catalog.filters.category"
        @click="catalog.filters.category = ''"
      >
        全部美食
      </button>
      <button
        v-for="category in cuisineCategories"
        :key="category.code"
        type="button"
        :aria-pressed="catalog.filters.category === category.code"
        @click="catalog.filters.category = category.code"
      >
        {{ category.name }}
      </button>
    </nav>
    <div class="filter-toolbar" role="group" aria-label="餐厅筛选">
      <NearbyLocation />
      <button
        class="hide-closed"
        type="button"
        :aria-pressed="catalog.filters.hideClosed"
        @click="catalog.filters.hideClosed = !catalog.filters.hideClosed"
      >
        <span class="status-check" aria-hidden="true">{{
          catalog.filters.hideClosed ? '✓' : ''
        }}</span>
        隐藏停业餐厅
      </button>
      <RestaurantAdvancedFilters campus-filters />
      <RandomRestaurant />
      <p class="catalog-result-count" role="status" aria-live="polite">
        {{ catalog.loading ? '正在加载…' : `共 ${catalog.total} 家餐厅与食堂` }}
      </p>
    </div>
    <div
      v-if="
        catalog.filters.maxPrice !== null ||
        catalog.filters.maxDistanceMeters !== null ||
        catalog.filters.restaurantType ||
        catalog.filters.diningMode
      "
      class="advanced-tags"
      aria-label="已应用的高级筛选"
    >
      <button
        v-if="catalog.filters.restaurantType"
        type="button"
        aria-label="移除餐厅类型筛选"
        @click="catalog.filters.restaurantType = ''"
      >
        {{ catalog.filters.restaurantType === 'CANTEEN' ? '食堂' : '餐厅' }}
        <span aria-hidden="true">×</span>
      </button>
      <button
        v-if="catalog.filters.diningMode"
        type="button"
        aria-label="移除用餐方式筛选"
        @click="catalog.filters.diningMode = ''"
      >
        {{ catalog.filters.diningMode === 'dineIn' ? '支持堂食' : '支持外卖' }}
        <span aria-hidden="true">×</span>
      </button>
      <button
        v-if="catalog.filters.maxPrice !== null"
        type="button"
        :aria-label="`移除人均${catalog.filters.maxPrice}元以下筛选`"
        @click="catalog.filters.maxPrice = null"
      >
        人均 ≤ {{ catalog.filters.maxPrice }}元 <span aria-hidden="true">×</span>
      </button>
      <button
        v-if="catalog.filters.maxDistanceMeters !== null"
        type="button"
        :aria-label="`移除直线距离${catalog.filters.maxDistanceMeters}米以内筛选`"
        @click="catalog.filters.maxDistanceMeters = null"
      >
        直线距离 ≤ {{ catalog.filters.maxDistanceMeters }}米 <span aria-hidden="true">×</span>
      </button>
    </div>
  </div>
</template>

<style scoped src="./restaurant-filters.css"></style>
