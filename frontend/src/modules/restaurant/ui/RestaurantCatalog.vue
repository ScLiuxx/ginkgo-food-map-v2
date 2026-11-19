<script setup lang="ts">
import ResourceLink from '@/shared/ui/ResourceLink.vue'
import { computed, nextTick, onMounted, onUnmounted, useTemplateRef, watch } from 'vue'
import { useRestaurantCatalog } from '../model/catalog.store'
import RestaurantFilters from './RestaurantFilters.vue'
import RestaurantCard from './RestaurantCard.vue'
import SubmitRestaurantButton from './SubmitRestaurantButton.vue'

const catalog = useRestaurantCatalog()
const hasFilters = computed(
  () =>
    !!catalog.filters.restaurantType ||
    !!catalog.filters.diningMode ||
    !!catalog.filters.category ||
    catalog.filters.hideClosed ||
    catalog.filters.maxPrice !== null ||
    catalog.filters.maxDistanceMeters !== null ||
    catalog.filters.sort !== 'auto',
)
const loadTrigger = useTemplateRef('loadTrigger')
let media: ReturnType<typeof globalThis.matchMedia> | undefined
let observer: InstanceType<typeof globalThis.IntersectionObserver> | undefined
/** 屏幕模式改变时重新按对应批量读取，不混用8条分页与20条连续批次。 */
function updateMode(): void {
  catalog.activate(media?.matches ?? false)
}
onMounted(() => {
  media = globalThis.matchMedia?.('(max-width: 700px)')
  media?.addEventListener('change', updateMode)
  updateMode()
})
onUnmounted(() => {
  media?.removeEventListener('change', updateMode)
  observer?.disconnect()
  catalog.pause()
})
// 每次追加完成后重新观察，短列表的末尾仍在屏内时也能继续加载；失败只由用户重试。
watch(
  () => [
    loadTrigger.value,
    catalog.hasMore,
    catalog.loading,
    catalog.loadingMore,
    catalog.moreError,
  ],
  () => {
    observer?.disconnect()
    if (
      !loadTrigger.value ||
      !catalog.hasMore ||
      catalog.loading ||
      catalog.loadingMore ||
      catalog.moreError
    )
      return
    if (!globalThis.IntersectionObserver) return
    observer = new globalThis.IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting) && !catalog.moreError)
          void catalog.loadMore()
      },
      { rootMargin: '240px 0px' },
    )
    observer.observe(loadTrigger.value)
  },
  { flush: 'post' },
)
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
  <section
    id="restaurant-directory"
    class="restaurant-directory"
    aria-labelledby="directory-title"
    :aria-busy="catalog.loading"
  >
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
        <strong>{{ catalog.total }}</strong> 家餐厅<span v-if="catalog.continuous">
          · 已显示 {{ catalog.visibleRestaurants.length }} 家</span
        ><span v-else-if="catalog.total"> · 第 {{ catalog.page }} / {{ catalog.pages }} 页</span>
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
    <div v-else class="directory-empty directory-no-results" role="status">
      <h3>
        {{
          hasFilters
            ? '暂无符合条件的店铺'
            : catalog.filters.keyword.trim()
              ? '没有找到相关店铺'
              : '暂无店铺'
        }}
      </h3>
      <div class="empty-actions">
        <button
          v-if="hasFilters || catalog.filters.keyword.trim()"
          type="button"
          class="primary-action"
          @click="catalog.resetFilters"
        >
          {{ hasFilters ? '清除筛选' : '清空搜索' }}
        </button>
        <SubmitRestaurantButton
          v-if="catalog.resultsCurrent && catalog.total === 0"
          :keyword="catalog.filters.keyword"
          >添加店铺</SubmitRestaurantButton
        >
      </div>
    </div>
    <!-- 按钮同时是无观察器环境和键盘操作的后备入口，常规浏览由接近底部自动触发。 -->
    <div v-if="catalog.hasMore && !catalog.error" ref="loadTrigger" class="catalog-load-more">
      <template v-if="catalog.moreError">
        <p role="alert">{{ catalog.moreError }}</p>
        <button type="button" @click="catalog.loadMore">重试</button>
      </template>
      <p v-else-if="catalog.loadingMore" role="status">正在加载…</p>
      <button v-else type="button" @click="catalog.loadMore">加载更多</button>
    </div>
    <p
      v-if="catalog.visibleRestaurants.some((r) => r.tags.includes('OSM测试数据'))"
      class="osm-attribution"
    >
      © OpenStreetMap contributors ·
      <ResourceLink
        href="https://www.openstreetmap.org/copyright"
        target="_blank"
        rel="noopener noreferrer"
        >ODbL-1.0</ResourceLink
      >
    </p>
    <nav
      v-if="!catalog.continuous && !catalog.error && catalog.pages > 1"
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
    <!-- 只在当前条件的末页成功加载后提示，避免将加载中或查询失败误示为已经到底。 -->
    <div
      v-if="
        !catalog.loading &&
        !catalog.error &&
        catalog.resultsCurrent &&
        catalog.visibleRestaurants.length > 0 &&
        catalog.atEnd
      "
      class="directory-add"
    >
      <p>没有找到想找的店铺？欢迎补充</p>
      <SubmitRestaurantButton :keyword="catalog.filters.keyword">添加店铺</SubmitRestaurantButton>
    </div>
  </section>
</template>

<style scoped src="./restaurant-catalog.css"></style>
