import { toErrorMessage } from '../../shared/api/request'
import { isLoggedIn } from '../../shared/auth/session'
import { fetchCategories, fetchRestaurants } from '../../modules/restaurant/api'
import {
  toRestaurantView,
  type RestaurantCategory,
  type RestaurantSort,
  type RestaurantView,
} from '../../modules/restaurant/types'

type HomeData = {
  categories: RestaurantCategory[]
  activeCategory: string
  keyword: string
  sortLabels: string[]
  sortValues: RestaurantSort[]
  sortIndex: number
  records: RestaurantView[]
  loading: boolean
  loadingMore: boolean
  errorMessage: string
  total: number
  page: number
  totalPages: number
  accessiblePages: number
  hasMore: boolean
  loggedIn: boolean
}

type HomeCustom = {
  requestSequence: number
  searchTimer: number | null
  loadCategories(): Promise<void>
  loadPage(reset: boolean): Promise<void>
  loadMore(): void
  refresh(): Promise<void>
}

const PAGE_SIZE = 8

/**
 * 找店首页。
 *
 * 所有筛选条件交给服务端分页，前端不在当前页二次过滤；
 * 请求序号用于丢弃迟到响应，避免快速切换分类或关键词时旧结果覆盖新列表。
 */
Page<HomeData, HomeCustom>({
  data: {
    categories: [],
    activeCategory: '',
    keyword: '',
    sortLabels: ['综合排序', '评分优先', '点评最多', '人均最低'],
    sortValues: ['auto', 'score', 'reviews', 'price'],
    sortIndex: 0,
    records: [],
    loading: true,
    loadingMore: false,
    errorMessage: '',
    total: 0,
    page: 1,
    totalPages: 0,
    accessiblePages: 0,
    hasMore: false,
    loggedIn: false,
  },

  requestSequence: 0,
  searchTimer: null,

  onLoad() {
    this.setData({ loggedIn: isLoggedIn() })
    void this.loadCategories()
    void this.loadPage(true)
  },

  onShow() {
    const tabBar = this.getTabBar?.()
    if (tabBar) tabBar.setData({ selected: 0 })
    tabBar?.refreshUnread?.()
    // 从登录页或详情页返回时重新读取本地会话，避免按钮状态停留在旧值。
    this.setData({ loggedIn: isLoggedIn() })
  },

  onPullDownRefresh() {
    void this.refresh()
  },

  onReachBottom() {
    this.loadMore()
  },

  /** 分类失败不阻塞餐厅列表；页面会保留“全部”并继续展示可浏览内容。 */
  async loadCategories() {
    try {
      const categories = await fetchCategories()
      this.setData({ categories: categories.filter((item) => item.enabled) })
    } catch {
      this.setData({ categories: [] })
    }
  },

  async loadPage(reset: boolean) {
    if (!reset && (this.data.loadingMore || !this.data.hasMore)) return
    const sequence = ++this.requestSequence
    const page = reset ? 1 : this.data.page + 1
    this.setData(
      reset
        ? { loading: true, loadingMore: false, errorMessage: '' }
        : { loadingMore: true, errorMessage: '' },
    )

    try {
      const result = await fetchRestaurants({
        keyword: this.data.keyword,
        category: this.data.activeCategory,
        sort: this.data.sortValues[this.data.sortIndex],
        hideClosed: true,
        page,
        pageSize: PAGE_SIZE,
      })
      if (sequence !== this.requestSequence) return

      const incoming = result.records.map(toRestaurantView)
      const records = reset ? incoming : this.data.records.concat(incoming)
      const accessiblePages = result.accessiblePages ?? result.totalPages
      this.setData({
        records,
        total: result.total,
        page: result.page,
        totalPages: result.totalPages,
        accessiblePages,
        hasMore: result.page < accessiblePages && incoming.length > 0,
        loading: false,
        loadingMore: false,
        errorMessage: '',
      })
    } catch (reason) {
      if (sequence !== this.requestSequence) return
      this.setData({
        loading: false,
        loadingMore: false,
        errorMessage: toErrorMessage(reason),
        ...(reset ? { records: [] } : {}),
      })
    } finally {
      if (reset) wx.stopPullDownRefresh()
    }
  },

  loadMore() {
    void this.loadPage(false)
  },

  async refresh() {
    if (!this.data.categories.length) await this.loadCategories()
    await this.loadPage(true)
  },

  onKeywordInput(event: WxInputEvent) {
    this.setData({ keyword: event.detail.value })
    if (this.searchTimer !== null) clearTimeout(this.searchTimer)
    // 输入停顿后再查询，避免每个汉字都发起一次全量检索。
    this.searchTimer = setTimeout(() => {
      this.searchTimer = null
      void this.loadPage(true)
    }, 350)
  },

  onSearchConfirm() {
    if (this.searchTimer !== null) {
      clearTimeout(this.searchTimer)
      this.searchTimer = null
    }
    void this.loadPage(true)
  },

  onClearSearch() {
    if (this.searchTimer !== null) {
      clearTimeout(this.searchTimer)
      this.searchTimer = null
    }
    if (!this.data.keyword) return
    this.setData({ keyword: '' })
    void this.loadPage(true)
  },

  onCategoryTap(event: WxTapEvent) {
    const code = String(event.currentTarget.dataset.code ?? '')
    if (code === this.data.activeCategory) return
    this.setData({ activeCategory: code })
    void this.loadPage(true)
  },

  onSortChange(event: WxPickerEvent) {
    const index = Number(event.detail.value)
    if (!Number.isInteger(index) || index < 0 || index >= this.data.sortValues.length) return
    if (index === this.data.sortIndex) return
    this.setData({ sortIndex: index })
    void this.loadPage(true)
  },

  onRestaurantTap(event: WxTapEvent) {
    const id = String(event.currentTarget.dataset.id ?? '')
    if (!id) return
    wx.navigateTo({ url: `/pages/restaurant/detail/index?id=${encodeURIComponent(id)}` })
  },

  onLoginTap() {
    wx.navigateTo({ url: `/pages/login/index?redirect=${encodeURIComponent('/pages/home/index')}` })
  },

  onRetry() {
    void this.loadPage(true)
  },

  onUnload() {
    // 页面卸载后不能再用定时器触发请求。
    if (this.searchTimer !== null) {
      clearTimeout(this.searchTimer)
      this.searchTimer = null
    }
    this.requestSequence++
  },
})
