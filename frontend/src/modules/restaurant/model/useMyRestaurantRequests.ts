import { onBeforeUnmount, ref, watch } from 'vue'
import axios from 'axios'
import { useLoginStore } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import {
  fetchMyRequestPage,
  fetchMyRequestDetail,
  fetchMyRequestPhoto,
  type MyRequestPage,
  type MyRequestDetail,
  type MyRequestFilter,
} from '../api/myRestaurantRequestsApi'

/** 只读本人记录；列表、详情、图片独立失败，固定请求凭证并隔离翻页/换号后的迟到结果。 */
export function useMyRestaurantRequests() {
  const auth = useLoginStore()
  const filter = ref<MyRequestFilter>('ALL'),
    result = ref<MyRequestPage | null>(null),
    loading = ref(false),
    error = ref('')
  const selectedId = ref<string | null>(null),
    detail = ref<MyRequestDetail | null>(null),
    detailLoading = ref(false),
    detailError = ref('')
  const photos = ref<
    { position: number; src: string; loading: boolean; error: string; attempt: number }[]
  >([])
  let context = 0,
    listVersion = 0,
    detailVersion = 0,
    requestedPage = 1
  let listAbort: AbortController | undefined, detailAbort: AbortController | undefined
  function clearDetail(clearSelection = true) {
    detailVersion++
    detailAbort?.abort()
    if (clearSelection) selectedId.value = null
    detail.value = null
    detailLoading.value = false
    detailError.value = ''
    photos.value.forEach((photo) => {
      if (photo.src) URL.revokeObjectURL(photo.src)
    })
    photos.value = []
  }
  /** 查询失败不保留旧列表和统计冒充当前筛选结果，重试使用原请求页码。 */
  async function load(page = requestedPage) {
    const token = authToken.value,
      userId = auth.authenticated && auth.user ? String(auth.user.id) : null
    if (!token || !userId) return
    const version = ++listVersion,
      owner = context,
      status = filter.value
    listAbort?.abort()
    listAbort = new AbortController()
    clearDetail()
    requestedPage = page
    loading.value = true
    error.value = ''
    result.value = null
    try {
      const data = await fetchMyRequestPage(status, page, userId, token, listAbort.signal)
      if (version === listVersion && owner === context) result.value = data
    } catch (reason) {
      if (version === listVersion && owner === context) error.value = toErrorMessage(reason)
    } finally {
      if (version === listVersion && owner === context) loading.value = false
    }
  }
  function changeFilter(value: MyRequestFilter) {
    filter.value = value
    void load(1)
  }
  /** 最多五张申请附图；关闭详情、切换申请或换号时中止网络并释放所有已创建URL。 */
  async function loadPhoto(position: number) {
    const photo = photos.value.find((row) => row.position === position),
      token = authToken.value,
      requestId = selectedId.value
    if (!photo || photo.loading || !token || !requestId || !detailAbort) return
    const version = detailVersion,
      owner = context,
      signal = detailAbort.signal,
      attempt = ++photo.attempt
    photo.loading = true
    photo.error = ''
    try {
      const blob = await fetchMyRequestPhoto(requestId, position, token, signal)
      if (
        version !== detailVersion ||
        owner !== context ||
        signal.aborted ||
        attempt !== photo.attempt
      )
        return
      if (photo.src) URL.revokeObjectURL(photo.src)
      photo.src = URL.createObjectURL(blob)
    } catch (reason) {
      if (version === detailVersion && owner === context && attempt === photo.attempt)
        photo.error = toErrorMessage(reason)
    } finally {
      if (version === detailVersion && owner === context && attempt === photo.attempt)
        photo.loading = false
    }
  }
  function imageFailed(position: number) {
    const photo = photos.value.find((row) => row.position === position)
    if (photo?.src) {
      URL.revokeObjectURL(photo.src)
      photo.src = ''
      photo.error = '图片无法显示，请重新加载。'
    }
  }
  async function select(requestId: string) {
    const token = authToken.value,
      userId = auth.authenticated && auth.user ? String(auth.user.id) : null
    if (!token || !userId) return
    clearDetail(false)
    selectedId.value = requestId
    detailLoading.value = true
    detailAbort = new AbortController()
    const version = detailVersion,
      owner = context
    try {
      const data = await fetchMyRequestDetail(requestId, userId, token, detailAbort.signal)
      if (version !== detailVersion || owner !== context) return
      detail.value = data
      photos.value = data.photoPositions.map((position) => ({
        position,
        src: '',
        loading: false,
        error: '',
        attempt: 0,
      }))
      photos.value.forEach((photo) => void loadPhoto(photo.position))
    } catch (reason) {
      if (version === detailVersion && owner === context)
        detailError.value =
          axios.isAxiosError(reason) && reason.response?.status === 404
            ? '内容已失效或不可访问。'
            : toErrorMessage(reason)
    } finally {
      if (version === detailVersion && owner === context) detailLoading.value = false
    }
  }
  watch(
    () => [auth.authenticated, auth.user?.id, authToken.value],
    () => {
      context++
      listVersion++
      listAbort?.abort()
      clearDetail()
      result.value = null
      error.value = ''
      loading.value = false
      filter.value = 'ALL'
      requestedPage = 1
      if (auth.authenticated && authToken.value) void load(1)
    },
    { immediate: true, flush: 'sync' },
  )
  onBeforeUnmount(() => {
    context++
    listVersion++
    listAbort?.abort()
    clearDetail()
  })
  return {
    filter,
    result,
    loading,
    error,
    selectedId,
    detail,
    detailLoading,
    detailError,
    photos,
    load,
    changeFilter,
    select,
    clearDetail,
    loadPhoto,
    imageFailed,
  }
}
