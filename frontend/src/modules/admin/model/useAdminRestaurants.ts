import { computed, onBeforeUnmount, ref, watch, type Ref } from 'vue'
import {
  fetchRestaurants,
  fetchRestaurantCategories,
  fetchRestaurantManagement,
  deleteRestaurant,
  restoreRestaurant,
  useRestaurantCatalog,
  type RestaurantReadModel,
  type RestaurantCategory,
  type RestaurantManagement,
} from '@/modules/restaurant'
import { authToken, getAccessToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'

/** 管理列表只使用真实分页；删除快照单独授权读取，不将公开目录冒充回收站。 */
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
    notice = ref(''),
    lookupId = ref('')
  const selected = ref<RestaurantManagement | null>(null)
  const inspecting = ref(false),
    busy = ref(false),
    actionError = ref('')
  const confirming = ref(false),
    sessionChanged = ref(false)
  const token = getAccessToken()
  const catalog = useRestaurantCatalog()
  let revision = 0,
    inspection = 0,
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

  /** 按ID查询可读取删除资料；打开另一商户立即清除旧确认与错误。 */
  async function inspect(id = lookupId.value.trim()) {
    if (busy.value || !validSession()) return
    const current = ++inspection
    selected.value = null
    confirming.value = false
    actionError.value = ''
    if (!/^[1-9]\d*$/.test(id)) {
      actionError.value = '请输入有效的店铺 ID。'
      return
    }
    inspecting.value = true
    try {
      const result = await fetchRestaurantManagement(id, token!)
      if (current === inspection && validSession()) selected.value = result
    } catch (reason) {
      if (current === inspection && validSession()) actionError.value = toErrorMessage(reason)
    } finally {
      if (current === inspection) inspecting.value = false
    }
  }

  /** 写入不自动重试；结果不明时提示重新查询，成功后保留ID便于恢复。 */
  async function applyAction() {
    const snapshot = selected.value
    if (!snapshot || !confirming.value || busy.value || !validSession()) return
    busy.value = true
    actionError.value = ''
    const id = snapshot.restaurant.id
    try {
      const result = snapshot.deleted
        ? await restoreRestaurant(id, token!)
        : await deleteRestaurant(id, token!)
      if (!validSession()) return
      selected.value = result
      confirming.value = false
      catalog.invalidate()
      notice.value = `「${result.restaurant.name}」已${result.deleted ? '删除' : '恢复'}，店铺 ID：${id}。`
      await reload()
    } catch (reason) {
      if (validSession())
        actionError.value = `${toErrorMessage(reason)}。请重新按 ID 查询当前状态后再决定是否重试。`
    } finally {
      busy.value = false
    }
  }

  function close() {
    if (busy.value) return
    inspection++
    inspecting.value = false
    selected.value = null
    confirming.value = false
    actionError.value = ''
  }
  function search() {
    appliedKeyword.value = keyword.value.trim()
    appliedCategory.value = category.value
    close()
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
      close()
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
    inspection++
    controller?.abort()
    items.value = []
    selected.value = null
  })
  onBeforeUnmount(() => {
    disposed = true
    revision++
    inspection++
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
    lookupId,
    selected,
    inspecting,
    busy,
    actionError,
    confirming,
    sessionChanged,
    reload,
    loadCategories,
    inspect,
    applyAction,
    close,
    search,
  }
}
