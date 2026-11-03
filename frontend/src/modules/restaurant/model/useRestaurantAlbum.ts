import { computed, onScopeDispose, ref, watch } from 'vue'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import {
  fetchRestaurantAlbum,
  fetchRestaurantAlbumImage,
  type RestaurantAlbumPhoto,
} from '../api/restaurantAlbumApi'

export interface AlbumEntry extends RestaurantAlbumPhoto {
  src: string
  loading: boolean
  error: string
}

/** 相册与点评生命周期独立；列表分页有界，JPEG最多四个并发，代次隔离旧商户的迟到结果。 */
export function useRestaurantAlbum(restaurantId: () => string) {
  const photos = ref<AlbumEntry[]>([]),
    selectedId = ref(''),
    nextCursor = ref<string | null>(null)
  const loading = ref(false),
    loadingMore = ref(false),
    error = ref(''),
    moreError = ref('')
  const selected = computed(
    () => photos.value.find((photo) => photo.id === selectedId.value) ?? photos.value[0],
  )
  let generation = 0,
    disposed = false
  let controller = new AbortController()
  let workers = { active: 0, queue: [] as AlbumEntry[] }

  function release() {
    photos.value.forEach((photo) => {
      if (photo.src) URL.revokeObjectURL(photo.src)
    })
    photos.value = []
  }

  /** 旧代次只结束自己的工作池，不能减少新页面的并发计数或创建失去归属的Blob。 */
  function pump() {
    const pool = workers,
      ticket = generation,
      id = restaurantId(),
      signal = controller.signal
    while (!disposed && pool.active < 4 && pool.queue.length) {
      const photo = pool.queue.shift()!
      pool.active++
      void fetchRestaurantAlbumImage(id, photo.id, signal)
        .then((blob) => {
          if (disposed || ticket !== generation || signal.aborted) return
          photo.src = URL.createObjectURL(blob)
        })
        .catch((reason) => {
          if (!disposed && ticket === generation && !signal.aborted)
            photo.error = '图片加载失败：' + toErrorMessage(reason)
        })
        .finally(() => {
          pool.active--
          if (!disposed && ticket === generation) {
            photo.loading = false
            pump()
          }
        })
    }
  }

  function loadPhoto(id: string, priority = true) {
    const photo = photos.value.find((entry) => entry.id === id)
    if (!photo || photo.src || disposed) return
    if (photo.loading) {
      // 用户点到仍在排队的缩略图时优先加载，正在进行的请求仍遵守并发上限。
      const queued = workers.queue.findIndex((entry) => entry.id === id)
      if (priority && queued >= 0) workers.queue.unshift(...workers.queue.splice(queued, 1))
      return
    }
    photo.loading = true
    photo.error = ''
    if (priority) workers.queue.unshift(photo)
    else workers.queue.push(photo)
    pump()
  }

  /** JPEG解码失败不冒充空相册，也不静默改变封面，保留原位置允许单张重试。 */
  function failPhoto(id: string) {
    const photo = photos.value.find((entry) => entry.id === id)
    if (!photo) return
    if (photo.src) URL.revokeObjectURL(photo.src)
    photo.src = ''
    photo.error = '图片无法显示，请重试'
    photo.loading = false
  }

  async function loadPage(first: boolean) {
    const ticket = generation,
      id = restaurantId(),
      signal = controller.signal,
      cursor = first ? null : nextCursor.value
    if (first) {
      loading.value = true
      error.value = ''
    } else {
      loadingMore.value = true
      moreError.value = ''
    }
    try {
      const result = await fetchRestaurantAlbum(id, cursor, signal)
      if (disposed || ticket !== generation || signal.aborted) return
      if (result.nextCursor !== null && (result.nextCursor === cursor || !result.records.length))
        throw new Error('相册分页未推进，请重新加载')
      const existing = new Set(photos.value.map((entry) => entry.id))
      photos.value.push(
        ...result.records
          .filter((entry) => !existing.has(entry.id))
          .map((entry) => ({ ...entry, src: '', loading: false, error: '' })),
      )
      nextCursor.value = result.nextCursor
      if (!selectedId.value)
        selectedId.value =
          photos.value.find((entry) => entry.cover)?.id ?? photos.value[0]?.id ?? ''
      // 封面优先，剩余缩略图通过同一个四并发工作池加载。
      if (selectedId.value) loadPhoto(selectedId.value)
      photos.value.filter((entry) => !entry.error).forEach((entry) => loadPhoto(entry.id, false))
    } catch (reason) {
      if (disposed || ticket !== generation || signal.aborted) return
      if (first) error.value = toErrorMessage(reason)
      else moreError.value = toErrorMessage(reason)
    } finally {
      if (!disposed && ticket === generation) {
        loading.value = false
        loadingMore.value = false
      }
    }
  }

  function reload() {
    generation++
    controller.abort()
    controller = new AbortController()
    workers = { active: 0, queue: [] }
    release()
    selectedId.value = ''
    nextCursor.value = null
    error.value = ''
    moreError.value = ''
    loadingMore.value = false
    void loadPage(true)
  }
  async function loadMore() {
    if (loading.value || loadingMore.value || !nextCursor.value) return
    await loadPage(false)
  }
  function selectPhoto(id: string) {
    selectedId.value = id
    loadPhoto(id)
  }
  watch(restaurantId, reload, { immediate: true })
  onScopeDispose(() => {
    disposed = true
    generation++
    controller.abort()
    workers.queue = []
    release()
  })
  return {
    photos,
    selected,
    selectedId,
    nextCursor,
    loading,
    loadingMore,
    error,
    moreError,
    reload,
    loadMore,
    selectPhoto,
    loadPhoto,
    failPhoto,
  }
}
