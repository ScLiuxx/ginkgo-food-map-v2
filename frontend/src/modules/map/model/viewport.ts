import { computed, onScopeDispose, ref, watch } from 'vue'
import {
  fetchRestaurants,
  type CatalogFilters,
  type RestaurantReadModel,
} from '@/modules/restaurant'
import { fetchMapPoints, type MapBounds, type MapPoint } from '../api/mapApi'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'

/** 地图会话状态：分页只更新列表，移动/筛选同时更新点位；成功后原子替换，失败保留并标记旧结果。 */
export function useViewport(filters: CatalogFilters, enabled = true) {
  const bounds = ref<MapBounds>()
  const page = ref(1),
    total = ref(0),
    pages = ref(1)
  const rows = ref<RestaurantReadModel[]>([]),
    points = ref<MapPoint[]>([])
  // 只有当前筛选成功完成后，空结果才可用于展示新店投稿入口。
  const successfulFilters = ref('')
  const loading = ref(false),
    error = ref<string | null>(null),
    truncated = ref(false)
  let sequence = 0,
    controller: AbortController | undefined,
    timer: ReturnType<typeof setTimeout> | undefined
  let loadedKey = ''
  function cancel() {
    sequence++
    controller?.abort()
    clearTimeout(timer)
    loading.value = false
  }
  async function load() {
    if (!enabled) return
    cancel()
    const ticket = sequence,
      filterKey = JSON.stringify(filters),
      snapshot = { ...filters },
      searching = Boolean(snapshot.keyword.trim()),
      area = !searching && bounds.value ? { ...bounds.value } : undefined
    // 此轮新条件仅用于首页；地图点位接口尚无对应能力，排除共享状态中的新字段，避免点位与列表口径不同。
    delete snapshot.restaurantType
    delete snapshot.diningMode
    // 无用户位置时等待授权，清掉旧点位，避免继续呈现其他排序的结果。
    if (snapshot.sort === 'distance' && (snapshot.latitude == null || snapshot.longitude == null)) {
      rows.value = []
      points.value = []
      total.value = 0
      pages.value = 1
      loadedKey = ''
      return
    }
    const key = JSON.stringify([snapshot, area])
    const reloadPoints = key !== loadedKey
    controller = new AbortController()
    const signal = controller.signal
    loading.value = true
    error.value = null
    try {
      const [result, markers] = await Promise.all([
        fetchRestaurants(snapshot, page.value, 8, signal, area),
        area && reloadPoints ? fetchMapPoints(snapshot, area, signal) : Promise.resolve(null),
      ])
      if (ticket !== sequence || signal.aborted) return
      if (page.value > Math.max(1, result.totalPages)) {
        page.value = Math.max(1, result.totalPages)
        void load()
        return
      }
      rows.value = result.records
      total.value = result.total
      pages.value = Math.max(1, result.totalPages)
      if (searching) {
        // 全站搜索只画当前页的真实结果，不把当前地图框作为搜索条件。
        points.value = result.records.flatMap((row) =>
          !row.needsLocation && row.longitude !== null && row.latitude !== null
            ? [
                {
                  id: row.id,
                  name: row.name,
                  longitude: row.longitude,
                  latitude: row.latitude,
                  score: row.score,
                  pricePerPerson: row.price,
                  businessStatus: row.closed ? ('CLOSED' as const) : ('OPERATING' as const),
                },
              ]
            : [],
        )
        truncated.value = false
      } else if (markers) {
        points.value = markers.points
        truncated.value = markers.truncated
      }
      loadedKey = key
      successfulFilters.value = filterKey
    } catch (reason) {
      if (ticket === sequence && !signal.aborted) error.value = toErrorMessage(reason)
    } finally {
      if (ticket === sequence) loading.value = false
    }
  }
  /** moveend和zoomend通常连续到达，合并相同范围，立即使旧响应失效，300ms后只查询最后范围。 */
  function schedule() {
    if (!enabled) return
    cancel()
    loading.value = true
    timer = setTimeout(() => {
      void load()
    }, 300)
  }
  function move(area: MapBounds) {
    if (JSON.stringify(area) === JSON.stringify(bounds.value)) return
    bounds.value = area
    // 搜索时仍记住最新视野供清空搜索后浏览，但移动不重搜、不重置搜索分页。
    if (filters.keyword.trim()) return
    page.value = 1
    schedule()
  }
  watch(
    filters,
    () => {
      page.value = 1
      schedule()
    },
    { deep: true, flush: 'sync' },
  )
  function goToPage(value: number) {
    if (!loading.value && value >= 1 && value <= pages.value) {
      page.value = value
      void load()
    }
  }
  onScopeDispose(cancel)
  return {
    bounds,
    page,
    total,
    pages,
    rows,
    points,
    loading,
    error,
    truncated,
    move,
    load,
    goToPage,
    stale: computed(() => Boolean(error.value) || loading.value),
    resultsCurrent: computed(() => successfulFilters.value === JSON.stringify(filters)),
  }
}
