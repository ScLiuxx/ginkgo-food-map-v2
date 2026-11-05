import { defineStore } from 'pinia'
import { useUserLocation } from '@/modules/map/model/userLocation.store'
import { computed, onScopeDispose, reactive, ref, watch } from 'vue'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { fetchRestaurantCategories, fetchRestaurants } from '../api/restaurantApi'
import type { RestaurantCategory } from '../api/restaurantApi'
import type { CatalogFilters, RestaurantReadModel } from './restaurant.types'

/** 目录保留浏览条件和上一页结果；筛选、计数和分页统一由服务端执行。 */
export const useRestaurantCatalog = defineStore('restaurant-catalog', () => {
  const filters = reactive<CatalogFilters>({
    keyword: '',
    category: '',
    sort: 'auto',
    hideClosed: false,
    maxPrice: null,
    maxDistanceMeters: null,
  })
  const userLocation = useUserLocation()
  // 单次位置跨首页/地图共享，移除校区隐式过滤；每次重新定位后触发完整服务端排序。
  watch(
    () => userLocation.location,
    (position) => {
      filters.longitude = position?.center[0]
      filters.latitude = position?.center[1]
    },
    { immediate: true },
  )
  const page = ref(1)
  const pageSize = 8
  const total = ref(0)
  const pages = ref(1)
  const visibleRestaurants = ref<RestaurantReadModel[]>([])
  const categories = ref<RestaurantCategory[]>([])
  const loading = ref(false)
  const loadedFilters = ref('')
  // 防抖等待期间也须核对条件快照，不能用上一轮的空结果显示“未找到”投稿入口。
  const resultsCurrent = computed(() => loadedFilters.value === JSON.stringify(filters))
  let active = true
  const error = ref<string | null>(null)
  const searchNotice = ref('')
  let generation = 0
  let controller: AbortController | undefined
  let timer: ReturnType<typeof setTimeout> | undefined

  // 防止大量数据时为每个页码生成一个按钮，当前页附近仍可直接跳转。
  const pageNumbers = computed(() => {
    const start = Math.max(1, Math.min(page.value - 2, pages.value - 4))
    return [
      ...new Set([
        1,
        ...Array.from({ length: Math.min(5, pages.value) }, (_, index) => start + index),
        pages.value,
      ]),
    ]
  })

  /** 立即使旧响应失效；即使网络层不能中断，旧请求也不能覆盖新的筛选结果。 */
  function cancelPending(): void {
    generation += 1
    controller?.abort()
    clearTimeout(timer)
    timer = undefined
    loading.value = false
  }

  /** 直接进入或浏览器返回目录时重新读取；保留缓存卡片供滚动位置恢复。 */
  async function load(): Promise<void> {
    active = true
    cancelPending()
    const requestGeneration = generation
    controller = new AbortController()
    const signal = controller.signal
    loading.value = true
    error.value = null
    const snapshot = { ...filters }
    // 选中距离但尚未取得原点时等待定位，不能用后端无原点的稳定次序冒充距离排序。
    if (snapshot.sort === 'distance' && (snapshot.latitude == null || snapshot.longitude == null)) {
      visibleRestaurants.value = []
      total.value = 0
      pages.value = 1
      loading.value = false
      return
    }
    try {
      const [dictionary, result] = await Promise.all([
        categories.value.length
          ? Promise.resolve(categories.value)
          : fetchRestaurantCategories(signal),
        fetchRestaurants(snapshot, page.value, pageSize, signal),
      ])
      if (requestGeneration !== generation) return
      categories.value = dictionary
      total.value = result.total
      pages.value = Math.max(1, result.accessiblePages ?? result.totalPages)
      searchNotice.value = result.search && result.search.engine === 'MYSQL' && snapshot.keyword.trim()
        ? '当前使用基础关键词匹配。' + (result.search.effectiveSort === 'score' ? '按综合评分排序。' : '')
        : ''
      if (result.accessiblePages !== undefined && result.accessiblePages < result.totalPages)
        searchNotice.value += '结果较多，仅开放前10000条的分页，请缩小筛选范围。'
      // 删除等操作使最后一页消失时回到新末页，再请求，避免显示假空结果。
      if (page.value > pages.value) {
        page.value = pages.value
        return
      }
      visibleRestaurants.value = result.records
      loadedFilters.value = JSON.stringify(snapshot)
    } catch (reason) {
      if (requestGeneration !== generation || signal.aborted) return
      visibleRestaurants.value = []
      total.value = 0
      pages.value = 1
      error.value = toErrorMessage(reason)
    } finally {
      if (requestGeneration === generation) loading.value = false
    }
  }

  // 同步重置页码，再由批处理的查询watch统一发起一次请求。
  watch(
    filters,
    () => {
      page.value = 1
    },
    { deep: true, flush: 'sync' },
  )
  watch(
    () => [JSON.stringify(filters), page.value],
    (current, previous) => {
      if (!active) return
      cancelPending()
      visibleRestaurants.value = []
      total.value = 0
      error.value = null
      searchNotice.value = ''
      loading.value = true
      const keywordChanged =
        JSON.parse(String(current[0])).keyword !== JSON.parse(String(previous[0])).keyword
      if (keywordChanged)
        timer = setTimeout(() => {
          void load()
        }, 250)
      else void load()
    },
  )
  onScopeDispose(cancelPending)

  /** 重置条件；条件本就为空时也允许重新读取服务端新增的数据。 */
  function resetFilters(): void {
    const previous = JSON.stringify(filters)
    const wasFirstPage = page.value === 1
    Object.assign(filters, {
      ...(filters.restaurantType !== undefined ? { restaurantType: '' } : {}),
      ...(filters.diningMode !== undefined ? { diningMode: '' } : {}),
      keyword: '',
      category: '',
      sort: 'auto',
      hideClosed: false,
      maxPrice: null,
      maxDistanceMeters: null,
    })
    page.value = 1
    if (previous === JSON.stringify(filters) && wasFirstPage) void load()
  }

  /** 限制有效页码，加载中不允许重复翻页。 */
  function goToPage(value: number): void {
    if (!Number.isFinite(value) || loading.value) return
    page.value = Math.min(pages.value, Math.max(1, Math.trunc(value)))
  }

  /** 写入成功后丢弃陈旧卡片；保留浏览条件/页码，返回目录时由服务端重新校正页数。 */
  function invalidate(): void {
    cancelPending()
    visibleRestaurants.value = []
    total.value = 0
    error.value = null
  }

  return {
    /** 地图接管查询时只复用条件，暂停目录watch，避免同一操作重复发请求。 */
    pause: () => {
      active = false
      cancelPending()
    },
    filters,
    page,
    pageSize,
    total,
    pages,
    visibleRestaurants,
    categories,
    loading,
    resultsCurrent,
    error,
    searchNotice,
    pageNumbers,
    load,
    cancelPending,
    resetFilters,
    goToPage,
    invalidate,
  }
})
