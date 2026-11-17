import { defineStore } from 'pinia'
import { useUserLocation } from '@/modules/map/model/userLocation.store'
import { computed, onScopeDispose, reactive, ref, watch } from 'vue'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { fetchRestaurantCategories, fetchRestaurants } from '../api/restaurantApi'
import type { RestaurantCategory, RestaurantPage } from '../api/restaurantApi'
import type { CatalogFilters, RestaurantReadModel } from './restaurant.types'

/** 目录保留浏览条件；电脑分页，手机累积服务端批次，均不在前端重新排序。 */
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
      // 校区原点不受共享定位更新影响，尤其不能被迟到的GPS回调覆盖。
      if (filters.distanceOrigin && filters.distanceOrigin !== 'USER') return
      // 重新定位暂时清空位置时保留上次参照点，失败不能清空原列表。
      if (!position) return
      filters.longitude = position?.center[0]
      filters.latitude = position?.center[1]
    },
    { immediate: true },
  )
  const page = ref(1)
  const continuous = ref(false)
  const pageSize = computed(() => (continuous.value ? 20 : 8))
  const loadedPage = ref(0)
  const total = ref(0)
  const pages = ref(1)
  const visibleRestaurants = ref<RestaurantReadModel[]>([])
  const categories = ref<RestaurantCategory[]>([])
  const loading = ref(false)
  const loadingMore = ref(false)
  const moreError = ref<string | null>(null)
  const truncated = ref(false)
  const loadedFilters = ref('')
  // 防抖等待期间也须核对条件快照，不能用上一轮的空结果显示“未找到”投稿入口。
  const resultsCurrent = computed(() => loadedFilters.value === JSON.stringify(filters))
  const hasMore = computed(
    () =>
      continuous.value &&
      resultsCurrent.value &&
      loadedPage.value > 0 &&
      loadedPage.value < pages.value,
  )
  // 可访问窗口的末尾不等于所有结果的末尾，不能据此提示补充店铺。
  const atEnd = computed(
    () =>
      resultsCurrent.value &&
      loadedPage.value > 0 &&
      !truncated.value &&
      (continuous.value ? loadedPage.value : page.value) >= pages.value,
  )
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
    loadingMore.value = false
  }

  /** 应用服务端计数及降级提示；追加批次时同样复核可访问范围。 */
  function applyMetadata(result: RestaurantPage, snapshot: CatalogFilters): void {
    total.value = result.total
    pages.value = Math.max(1, result.accessiblePages ?? result.totalPages)
    truncated.value =
      result.accessiblePages !== undefined && result.accessiblePages < result.totalPages
    searchNotice.value =
      result.search?.engine === 'MYSQL' && snapshot.keyword.trim()
        ? '当前使用基础关键词匹配。' +
          (result.search.effectiveSort === 'score' ? '按综合评分排序。' : '')
        : ''
    if (truncated.value) searchNotice.value += '结果较多，仅开放前10000条，请缩小筛选范围。'
  }

  /** 主动重读从首批开始；返回手机目录应通过activate复用已成功加载的批次。 */
  async function load(): Promise<void> {
    active = true
    cancelPending()
    const requestGeneration = generation
    controller = new AbortController()
    const signal = controller.signal
    loading.value = true
    error.value = null
    moreError.value = null
    loadedPage.value = 0
    loadedFilters.value = ''
    if (continuous.value) visibleRestaurants.value = []
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
        fetchRestaurants(snapshot, continuous.value ? 1 : page.value, pageSize.value, signal),
      ])
      if (requestGeneration !== generation) return
      categories.value = dictionary
      applyMetadata(result, snapshot)
      // 删除等操作使最后一页消失时回到新末页，再请求，避免显示假空结果。
      if (page.value > pages.value) {
        page.value = pages.value
        return
      }
      visibleRestaurants.value = result.records
      loadedPage.value = continuous.value ? 1 : page.value
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

  /** 手机只在成功后推进批次；失败保留卡片与页码，重试仍读取同一批。 */
  async function loadMore(): Promise<void> {
    if (!active || !hasMore.value || loading.value || loadingMore.value) return
    const requestGeneration = generation
    const nextPage = loadedPage.value + 1
    const snapshot = { ...filters }
    controller = new AbortController()
    const signal = controller.signal
    loadingMore.value = true
    moreError.value = null
    try {
      const result = await fetchRestaurants(snapshot, nextPage, pageSize.value, signal)
      if (requestGeneration !== generation || signal.aborted) return
      // 中途异常空批不能导致观察器无限请求；若总页数因数据删除缩小，则正常结束。
      if (!result.records.length && nextPage <= (result.accessiblePages ?? result.totalPages))
        throw new Error('本批餐厅暂时无法加载，请重试。')
      applyMetadata(result, snapshot)
      const knownIds = new Set(visibleRestaurants.value.map((restaurant) => restaurant.id))
      for (const restaurant of result.records) {
        if (knownIds.has(restaurant.id)) continue
        knownIds.add(restaurant.id)
        visibleRestaurants.value.push(restaurant)
      }
      loadedPage.value = Math.min(nextPage, pages.value)
    } catch (reason) {
      if (requestGeneration === generation && !signal.aborted)
        moreError.value = toErrorMessage(reason)
    } finally {
      if (requestGeneration === generation) loadingMore.value = false
    }
  }

  /** 挂载或切换屏幕模式；只缓存当前条件的手机列表，不持久化到浏览器存储。 */
  function activate(mobile: boolean): void {
    active = true
    if (continuous.value !== mobile) {
      cancelPending()
      continuous.value = mobile
      page.value = 1
      // 模式属于查询watch的一部分，由同一批watch统一重读，避免重复请求。
      return
    }
    if (mobile && resultsCurrent.value && loadedPage.value > 0 && !error.value) return
    void load()
  }

  // 同步重置页码，再由批处理的查询watch统一发起一次请求。
  watch(
    filters,
    () => {
      // 同步隔离旧追加响应，防止筛选变化与下一次watch之间的微任务混入旧数据。
      cancelPending()
      page.value = 1
    },
    { deep: true, flush: 'sync' },
  )
  watch(
    () => [JSON.stringify(filters), page.value, continuous.value],
    (current, previous) => {
      if (!active) return
      cancelPending()
      visibleRestaurants.value = []
      loadedPage.value = 0
      loadedFilters.value = ''
      moreError.value = null
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
    if (continuous.value || !Number.isFinite(value) || loading.value) return
    page.value = Math.min(pages.value, Math.max(1, Math.trunc(value)))
  }

  /** 写入成功后丢弃陈旧卡片；保留浏览条件/页码，返回目录时由服务端重新校正页数。 */
  function invalidate(): void {
    cancelPending()
    visibleRestaurants.value = []
    loadedPage.value = 0
    loadedFilters.value = ''
    moreError.value = null
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
    continuous,
    loadingMore,
    moreError,
    hasMore,
    atEnd,
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
    loadMore,
    activate,
    cancelPending,
    resetFilters,
    goToPage,
    invalidate,
  }
})
