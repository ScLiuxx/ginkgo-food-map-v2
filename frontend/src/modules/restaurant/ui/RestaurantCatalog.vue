<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, useTemplateRef } from 'vue'
import { useRestaurantCatalog } from '../model/catalog.store'
import RestaurantFilters from './RestaurantFilters.vue'
import RestaurantCard from './RestaurantCard.vue'
import SubmitRestaurantButton from './SubmitRestaurantButton.vue'

const catalog = useRestaurantCatalog()
onMounted(() => {
  void catalog.load()
})
onUnmounted(catalog.cancelPending)
const resultsHeading = useTemplateRef('resultsHeading')
/** 分页继续由服务端完成；翻页后将键盘焦点移回结果栏，不重新排列当前页。 */
async function changePage(page: number): Promise<void> {
  catalog.goToPage(page)
  await nextTick()
  resultsHeading.value?.focus({ preventScroll: true })
  resultsHeading.value?.scrollIntoView?.({
    block: 'start',
    behavior: 'instant',
  })
}
</script>

<template>
  <section id="restaurant-directory" class="restaurant-directory" aria-labelledby="directory-title">
    <h2 id="directory-title" class="catalog-sr-only">餐厅与食堂目录</h2>
    <RestaurantFilters />
    <p v-if="catalog.searchNotice && !catalog.loading" role="status">{{ catalog.searchNotice }}</p>
    <div
      ref="resultsHeading"
      class="results-toolbar catalog-sr-only"
      tabindex="-1"
      aria-label="餐厅筛选结果"
    >
      <p role="status" aria-live="polite">
        {{ catalog.loading ? '正在加载 · ' : '' }}找到
        <strong>{{ catalog.total }}</strong> 家餐厅<span v-if="catalog.total">
          · 第 {{ catalog.page }} / {{ catalog.pages }} 页</span
        >
      </p>
    </div>
    <div v-if="catalog.error" class="directory-empty" role="alert">
      <h3>餐厅目录暂时无法加载。</h3>
      <p>{{ catalog.error }}</p>
      <button type="button" class="primary-action" @click="catalog.load">重新加载</button>
    </div>
    <div
      v-else-if="catalog.visibleRestaurants.length"
      class="restaurant-grid"
      :aria-busy="catalog.loading"
    >
      <RestaurantCard
        v-for="restaurant in catalog.visibleRestaurants"
        :key="restaurant.id"
        :restaurant="restaurant"
      />
    </div>
    <div
      v-else-if="
        catalog.filters.sort === 'distance' &&
        (catalog.filters.latitude == null || catalog.filters.longitude == null)
      "
      class="directory-empty"
      role="status"
    >
      <h3>取得位置后，按距离由近到远显示餐厅</h3>
      <p>请完成定位授权；若定位失败，可重试或切换其他排序。</p>
    </div>
    <div v-else-if="catalog.loading" class="directory-empty" role="status">正在加载餐厅…</div>
    <div v-else class="directory-empty" role="status">
      <h3>换个条件，也许就遇见了。</h3>
      <p>
        {{
          catalog.filters.diningMode
            ? '暂无已确认支持该用餐方式的商家，试试其他条件。'
            : '没有找到符合条件的餐厅，试试店名、地址或不同分类。'
        }}
      </p>
      <SubmitRestaurantButton
        v-if="catalog.filters.keyword.trim() && catalog.resultsCurrent && catalog.total === 0"
        :keyword="catalog.filters.keyword"
      />
      <button
        v-if="
          catalog.filters.keyword ||
          catalog.filters.restaurantType ||
          catalog.filters.diningMode ||
          catalog.filters.category ||
          catalog.filters.hideClosed ||
          catalog.filters.maxPrice !== null ||
          catalog.filters.maxDistanceMeters !== null ||
          catalog.filters.sort !== 'auto'
        "
        type="button"
        class="primary-action"
        @click="catalog.resetFilters"
      >
        清除筛选
      </button>
    </div>
    <div class="preview-notice" role="note">
      <p>营业、地址及消费请出发前核实。</p>
    </div>
    <p
      v-if="catalog.visibleRestaurants.some((r) => r.tags.includes('OSM测试数据'))"
      class="osm-attribution"
    >
      © OpenStreetMap contributors ·
      <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer"
        >ODbL-1.0</a
      >
    </p>
    <nav
      v-if="!catalog.error && catalog.pages > 1"
      class="catalog-pagination"
      aria-label="餐厅分页"
    >
      <button
        type="button"
        :disabled="catalog.loading || catalog.page === 1"
        @click="changePage(catalog.page - 1)"
      >
        ← 上一页
      </button>
      <button
        v-for="page in catalog.pageNumbers"
        :key="page"
        type="button"
        :aria-current="catalog.page === page ? 'page' : undefined"
        :aria-label="`第 ${page} 页`"
        :disabled="catalog.loading"
        @click="changePage(page)"
      >
        {{ page }}
      </button>
      <button
        type="button"
        :disabled="catalog.loading || catalog.page === catalog.pages"
        @click="changePage(catalog.page + 1)"
      >
        下一页 →
      </button>
    </nav>
  </section>
</template>

<style scoped src="./restaurant-catalog.css"></style>
