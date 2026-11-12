import { computed, onScopeDispose, ref, watch } from 'vue'
import type { CatalogFilters, RestaurantReadModel } from '@/modules/restaurant'
import { fetchMapPoints, type MapBounds, type MapPoint } from '../api/mapApi'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'

/** 恢复公开候选资料供页面立即呈现，返回后仍按保存的范围重新验证。 */
export interface ViewportSnapshot {
  bounds?: MapBounds
  total: number
  rows: RestaurantReadModel[]
  points: MapPoint[]
  truncated: boolean
  searchNotice: string
  error: string | null
}

/** 地图范围决定候选集：单次响应原子更新点位和卡片，搜索、筛选均不脱离该范围。 */
export function useViewport(filters: CatalogFilters, enabled = true, initial?: ViewportSnapshot) {
  const bounds = ref<MapBounds | undefined>(initial?.bounds)
  const total = ref(initial?.total ?? 0)
  const rows = ref<RestaurantReadModel[]>(initial?.rows ?? []),
    points = ref<MapPoint[]>(initial?.points ?? [])
  const successfulKey = ref('')
  const searchNotice = ref(initial?.searchNotice ?? '')
  const loading = ref(false),
    error = ref<string | null>(initial?.error ?? null),
    truncated = ref(initial?.truncated ?? false)
  let sequence = 0,
    controller: AbortController | undefined,
    timer: ReturnType<typeof setTimeout> | undefined
  const currentKey = () => JSON.stringify([filters, bounds.value])
  function cancel() {
    sequence++
    controller?.abort()
    clearTimeout(timer)
    loading.value = false
  }
  async function load() {
    if (!enabled) return
    cancel()
    // SDK尚未提供范围时保持空状态；失败也不偷偷改成全站目录。
    if (!bounds.value) return
    const ticket = sequence,
      key = currentKey(),
      snapshot = { ...filters },
      area = { ...bounds.value }
    if (snapshot.sort === 'distance' && (snapshot.latitude == null || snapshot.longitude == null)) {
      rows.value = []
      points.value = []
      total.value = 0
      truncated.value = false
      successfulKey.value = ''
      searchNotice.value = ''
      error.value = null
      return
    }
    controller = new AbortController()
    const signal = controller.signal
    loading.value = true
    error.value = null
    try {
      const result = await fetchMapPoints(snapshot, area, signal)
      if (ticket !== sequence || signal.aborted) return
      rows.value = result.records
      points.value = result.points
      total.value = result.matchedCount
      truncated.value = result.truncated
      searchNotice.value =
        result.search?.engine === 'MYSQL' && snapshot.keyword.trim()
          ? '当前在地图范围内使用基础关键词匹配。' +
            (result.search.effectiveSort === 'score' ? '按综合口碑分排序。' : '')
          : ''
      successfulKey.value = key
    } catch (reason) {
      if (ticket === sequence && !signal.aborted) error.value = toErrorMessage(reason)
    } finally {
      if (ticket === sequence) loading.value = false
    }
  }
  /** 连续地图事件合并为300ms后的最后范围；防抖等待期间也立即废弃旧响应。 */
  function schedule() {
    if (!enabled) return
    cancel()
    if (!bounds.value) return
    loading.value = true
    timer = setTimeout(() => {
      void load()
    }, 300)
  }
  function move(area: MapBounds) {
    if (JSON.stringify(area) === JSON.stringify(bounds.value)) return
    bounds.value = { ...area }
    schedule()
  }
  watch(filters, schedule, { deep: true, flush: 'sync' })
  onScopeDispose(cancel)
  return {
    /** 不缓存loading、定时器或请求控制器；未完成的查询在返回时使用新序号重发。 */
    snapshot: (): ViewportSnapshot => ({
      bounds: bounds.value && { ...bounds.value },
      total: total.value,
      rows: rows.value,
      points: points.value,
      truncated: truncated.value,
      searchNotice: searchNotice.value,
      error: error.value,
    }),
    bounds,
    total,
    rows,
    points,
    loading,
    error,
    searchNotice,
    truncated,
    move,
    load,
    stale: computed(() => Boolean(error.value) || loading.value),
    resultsCurrent: computed(() => successfulKey.value === currentKey()),
  }
}
