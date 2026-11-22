import { computed, onBeforeUnmount, ref, watch, type Ref } from 'vue'
import {
  fetchRestaurants,
  fetchRestaurantCategories,
  type RestaurantReadModel,
  type RestaurantCategory,
} from '@/modules/restaurant'
import { authToken, getAccessToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'

/** 管理列表负责查询分页；删除确认由共用弹窗独立处理。 */
export function useAdminRestaurants(enabled: Ref<boolean>) {
  const keyword = ref(''),
    category = ref(''),
    page = ref(1)
  const pageSize = ref(20),
    jumpPage = ref('')
  // 查询按钮应用筛选草稿，翻页不意外使用尚未提交的新条件。
  const appliedKeyword = ref(''),
    appliedCategory = ref('')
  const pageNumbers = computed(() => {
    const last = Math.max(1, totalPages.value)
    const start = Math.max(1, Math.min(page.value - 2, last - 4))
    return Array.from({ length: Math.min(5, last) }, (_, i) => start + i)
  })
  const items = ref<RestaurantReadModel[]>([]),
    categories = ref<RestaurantCategory[]>([])
  const total = ref(0),
    totalPages = ref(0),
    loading = ref(false),
    error = ref('')
  const categoryError = ref(''),
    notice = ref('')
  const busy = ref(false)
  const sessionChanged = ref(false)
  const token = getAccessToken()
  let revision = 0,
    disposed = false
  let controller: AbortController | undefined
  const validSession = () =>
    !disposed && !!token && token === getAccessToken() && !sessionChanged.value

  /** 查询取消与代际检查防止旧页覆盖新条件；错误时不继续显示旧结果。 */
  async function reload() {
    if (!enabled.value || !validSession()) return
    const current = ++revision
    controller?.abort()
    controller = new AbortController()
    loading.value = true
    error.value = ''
    items.value = []
    try {
      const result = await fetchRestaurants(
        {
          keyword: appliedKeyword.value,
          category: appliedCategory.value,
          sort: 'score',
          hideClosed: false,
          maxPrice: null,
          maxDistanceMeters: null,
        },
        page.value,
        pageSize.value,
        controller.signal,
      )
      if (current !== revision || !validSession()) return
      total.value = result.total
      totalPages.value = result.totalPages
      if (page.value > Math.max(1, result.totalPages)) {
        page.value = Math.max(1, result.totalPages)
        return
      }
      items.value = result.records
    } catch (reason) {
      if (current === revision && validSession() && !controller.signal.aborted)
        error.value = toErrorMessage(reason)
    } finally {
      if (current === revision) loading.value = false
    }
  }

  async function loadCategories() {
    categoryError.value = ''
    try {
      const result = await fetchRestaurantCategories()
      if (validSession()) categories.value = result.filter((item) => item.enabled)
    } catch (reason) {
      if (validSession()) categoryError.value = toErrorMessage(reason)
    }
  }

  function search() {
    appliedKeyword.value = keyword.value.trim()
    appliedCategory.value = category.value
    if (page.value !== 1) page.value = 1
    else void reload()
  }
  watch(page, () => {
    void reload()
  })
  watch(pageSize, () => {
    if (page.value !== 1) page.value = 1
    else void reload()
  })
  /** 跳页仅接受范围内整数，不向后端发送空白、小数和越界页。 */
  function jump() {
    if (busy.value || loading.value || !/^\d+$/.test(jumpPage.value)) return
    const value = Number(jumpPage.value)
    if (!Number.isSafeInteger(value) || value < 1 || value > totalPages.value) return
    page.value = value
    jumpPage.value = ''
  }
  watch(
    enabled,
    (value) => {
      if (value) {
        void reload()
        void loadCategories()
      } else {
        revision++
        controller?.abort()
        loading.value = false
      }
    },
    { immediate: true },
  )
  watch(authToken, () => {
    if (getAccessToken() === token) return
    sessionChanged.value = true
    revision++
    controller?.abort()
    items.value = []
  })
  onBeforeUnmount(() => {
    disposed = true
    revision++
    controller?.abort()
  })
  return {
    keyword,
    category,
    page,
    pageSize,
    pageNumbers,
    jumpPage,
    jump,
    items,
    categories,
    total,
    totalPages,
    loading,
    error,
    categoryError,
    notice,
    busy,
    sessionChanged,
    reload,
    loadCategories,
    search,
  }
}
