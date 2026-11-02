import { computed, onScopeDispose, ref, shallowRef, watch } from 'vue'
import { fetchRestaurants, type RestaurantReadModel } from '@/modules/restaurant'
import { searchAMapPlaces, type AMapPlace } from '../sdk/places'

export interface PlaceResult extends AMapPlace {
  match: RestaurantReadModel | null
  matchState: 'checking' | 'matched' | 'unmatched' | 'unavailable'
}
const normalizeName = (value: string) =>
  value.normalize('NFKC').replace(/\s+/g, '').toLocaleLowerCase()
/** 同名不能直接认作同一店；同名且80米内仅有一条本站记录才匹配，歧义保留未匹配，候选不完整另标待核实。 */
export function matchPlace(
  place: AMapPlace,
  candidates: RestaurantReadModel[],
): RestaurantReadModel | null {
  const rad = Math.PI / 180
  const matches = candidates.filter((row) => {
    if (
      normalizeName(row.name) !== normalizeName(place.name) ||
      row.needsLocation ||
      row.longitude === null ||
      row.latitude === null
    )
      return false
    const a =
      Math.sin(((row.latitude - place.latitude) * rad) / 2) ** 2 +
      Math.cos(row.latitude * rad) *
        Math.cos(place.latitude * rad) *
        Math.sin(((row.longitude - place.longitude) * rad) / 2) ** 2
    return 6371000 * 2 * Math.asin(Math.sqrt(Math.min(1, a))) <= 80
  })
  return matches.length === 1 ? matches[0]! : null
}

/** 高德与本站读取独立失败、独立分页；取消与序号双重拦截迟到SDK回调及匹配结果。 */
export function usePlaceSearch(keyword: () => string, enabled = true) {
  const records = shallowRef<PlaceResult[]>([]),
    loading = ref(false),
    error = ref<string | null>(null)
  const total = ref(0),
    page = ref(1)
  const pages = computed(() => Math.min(100, Math.max(1, Math.ceil(total.value / 10))))
  let sequence = 0,
    timer: ReturnType<typeof globalThis.setTimeout> | undefined,
    controller: AbortController | undefined
  function cancel() {
    sequence++
    controller?.abort()
    globalThis.clearTimeout(timer)
    loading.value = false
  }
  async function load() {
    cancel()
    const text = keyword().trim()
    if (!enabled || !text) {
      records.value = []
      total.value = 0
      error.value = null
      return
    }
    const ticket = sequence
    controller = new AbortController()
    const signal = controller.signal
    loading.value = true
    error.value = null
    records.value = []
    try {
      const result = await searchAMapPlaces(text, page.value, signal)
      if (ticket !== sequence || signal.aborted) return
      total.value = result.total
      records.value = result.places.map((place) => ({
        ...place,
        match: null,
        matchState: 'checking',
      }))
      loading.value = false
      let next = 0
      // 每页最多10条，三路并发读取100米范围内的本站候选；不受当前分类和视口影响。
      async function worker() {
        while (next < result.places.length && ticket === sequence && !signal.aborted) {
          const index = next++,
            place = result.places[index]!
          const dy = 100 / 111320,
            dx = dy / Math.max(0.1, Math.cos((place.latitude * Math.PI) / 180))
          const bounds = {
            west: Number((place.longitude - dx).toFixed(6)),
            east: Number((place.longitude + dx).toFixed(6)),
            south: Number((place.latitude - dy).toFixed(6)),
            north: Number((place.latitude + dy).toFixed(6)),
          }
          let match: RestaurantReadModel | null = null,
            matchState: PlaceResult['matchState'] = 'unavailable'
          try {
            const candidates = await fetchRestaurants(
              {
                keyword: '',
                category: '',
                sort: 'score',
                hideClosed: false,
                maxPrice: null,
                maxDistanceMeters: null,
              },
              1,
              100,
              signal,
              bounds,
            )
            if (candidates.total <= candidates.records.length) {
              match = matchPlace(place, candidates.records)
              matchState = match ? 'matched' : 'unmatched'
            }
          } catch {
            /* 查询失败只影响收录状态，不把失败当成未收录。 */
          }
          if (ticket !== sequence || signal.aborted) return
          records.value = records.value.map((row, i) =>
            i === index ? { ...row, match, matchState } : row,
          )
        }
      }
      void Promise.all([worker(), worker(), worker()])
    } catch (reason) {
      if (ticket === sequence && !signal.aborted) {
        total.value = 0
        error.value = reason instanceof Error ? reason.message : '高德地点搜索失败，请重试。'
      }
    } finally {
      if (ticket === sequence) loading.value = false
    }
  }
  watch(
    () => keyword().trim(),
    () => {
      cancel()
      records.value = []
      total.value = 0
      page.value = 1
      error.value = null
      if (!enabled || !keyword().trim()) return
      loading.value = true
      timer = globalThis.setTimeout(() => {
        void load()
      }, 500)
    },
    { immediate: true, flush: 'sync' },
  )
  function goToPage(value: number) {
    if (!loading.value && value >= 1 && value <= pages.value) {
      page.value = value
      void load()
    }
  }
  onScopeDispose(cancel)
  return { records, loading, error, total, page, pages, load, goToPage }
}
