import { toErrorMessage } from '../../shared/api/request'
import { isLoggedIn } from '../../shared/auth/session'
import { fetchCategories, fetchRestaurants } from '../../modules/restaurant/api'
import {
  toRestaurantView,
  type RestaurantCategory,
  type RestaurantResponse,
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
  filterOpen: boolean
  hideClosed: boolean
  maxPriceLabels: string[]
  maxPriceValues: Array<number | null>
  maxPriceIndex: number
  typeLabels: string[]
  typeValues: Array<'' | 'REGULAR' | 'CANTEEN'>
  typeIndex: number
  diningLabels: string[]
  diningValues: Array<'' | 'dineIn' | 'delivery'>
  diningIndex: number
  activeFilterCount: number
  randomVisible: boolean
  randomLoading: boolean
  randomError: string
  randomBudgetLabels: string[]
  randomBudgetValues: Array<number | null>
  randomBudgetIndex: number
  randomCandidateCount: number
  randomResult: RestaurantView | null
}

type HomeCustom = {
  requestSequence: number
  searchTimer: number | null
  randomSequence: number
  loadCategories(): Promise<void>
  loadPage(reset: boolean): Promise<void>
  loadMore(): void
  refresh(): Promise<void>
  openSubmission(): void
  toggleFilters(): void
  toggleHideClosed(): void
  onPriceChange(event: WxPickerEvent): void
  onTypeChange(event: WxPickerEvent): void
  onDiningChange(event: WxPickerEvent): void
  resetFilters(): void
  openRandom(): void
  closeRandom(): void
  rerollRandom(): void
  chooseRandom(): Promise<void>
  onRandomBudgetChange(event: WxPickerEvent): void
  openRandomResult(): void
  stopPropagation(): void
}

const PAGE_SIZE = 8
const RANDOM_PAGE_SIZE = 100

/**
 * 找店首页。
 *
 * 所有筛选条件交给服务端分页，前端不在当前页二次过滤；
 * 请求序号用于丢弃迟到响应，避免快速切换分类、筛选或关键词时旧结果覆盖新列表。
 * 随机推荐先在服务端按预算和营业状态收窄，再随机取一页，避免为抽一家店遍历全部结果。
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
    filterOpen: false,
    hideClosed: true,
    maxPriceLabels: ['不限人均', '≤15元', '≤30元', '≤50元', '≤100元'],
    maxPriceValues: [null, 15, 30, 50, 100],
    maxPriceIndex: 0,
    typeLabels: ['全部类型', '普通餐厅', '食堂'],
    typeValues: ['', 'REGULAR', 'CANTEEN'],
    typeIndex: 0,
    diningLabels: ['全部方式', '支持堂食', '支持外卖'],
    diningValues: ['', 'dineIn', 'delivery'],
    diningIndex: 0,
    activeFilterCount: 0,
    randomVisible: false,
    randomLoading: false,
    randomError: '',
    randomBudgetLabels: ['不限预算', '≤15元', '≤30元', '≤50元', '≤100元'],
    randomBudgetValues: [null, 15, 30, 50, 100],
    randomBudgetIndex: 0,
    randomCandidateCount: 0,
    randomResult: null,
  },

  requestSequence: 0,
  searchTimer: null,
  randomSequence: 0,

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

  /** 投稿页自行处理未登录状态，不在首页提前拦截，避免丢失当前浏览位置。 */
  openSubmission() {
    wx.navigateTo({ url: '/pages/submission/index' })
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
        hideClosed: this.data.hideClosed,
        restaurantType: this.data.typeValues[this.data.typeIndex],
        diningMode: this.data.diningValues[this.data.diningIndex],
        maxPrice: this.data.maxPriceValues[this.data.maxPriceIndex],
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

  toggleFilters() {
    this.setData({ filterOpen: !this.data.filterOpen })
  },

  /** 停业店铺默认隐藏；关闭后仍可浏览，列表中保留“停业”标记。 */
  toggleHideClosed() {
    this.setData({ hideClosed: !this.data.hideClosed })
    void this.loadPage(true)
  },

  onPriceChange(event: WxPickerEvent) {
    const index = Number(event.detail.value)
    if (!Number.isInteger(index) || index < 0 || index >= this.data.maxPriceValues.length) return
    if (index === this.data.maxPriceIndex) return
    this.setData({ maxPriceIndex: index })
    this.setData({
      activeFilterCount:
        (index > 0 ? 1 : 0) +
        (this.data.typeIndex > 0 ? 1 : 0) +
        (this.data.diningIndex > 0 ? 1 : 0),
    })
    void this.loadPage(true)
  },

  onTypeChange(event: WxPickerEvent) {
    const index = Number(event.detail.value)
    if (!Number.isInteger(index) || index < 0 || index >= this.data.typeValues.length) return
    if (index === this.data.typeIndex) return
    this.setData({ typeIndex: index })
    this.setData({
      activeFilterCount:
        (this.data.maxPriceIndex > 0 ? 1 : 0) +
        (index > 0 ? 1 : 0) +
        (this.data.diningIndex > 0 ? 1 : 0),
    })
    void this.loadPage(true)
  },

  onDiningChange(event: WxPickerEvent) {
    const index = Number(event.detail.value)
    if (!Number.isInteger(index) || index < 0 || index >= this.data.diningValues.length) return
    if (index === this.data.diningIndex) return
    this.setData({ diningIndex: index })
    this.setData({
      activeFilterCount:
        (this.data.maxPriceIndex > 0 ? 1 : 0) +
        (this.data.typeIndex > 0 ? 1 : 0) +
        (index > 0 ? 1 : 0),
    })
    void this.loadPage(true)
  },

  resetFilters() {
    this.setData({
      hideClosed: true,
      maxPriceIndex: 0,
      typeIndex: 0,
      diningIndex: 0,
      activeFilterCount: 0,
      filterOpen: false,
    })
    void this.loadPage(true)
  },

  /** 打开随机推荐；候选始终额外排除停业店铺，未知价格的店铺不纳入预算判断。 */
  openRandom() {
    this.setData({
      randomVisible: true,
      randomResult: null,
      randomCandidateCount: 0,
      randomError: '',
    })
    void this.chooseRandom()
  },

  closeRandom() {
    // 关闭弹层后迟到的响应不能再把结果写回页面。
    this.randomSequence++
    this.setData({ randomVisible: false, randomLoading: false, randomError: '' })
  },

  /** 阻止点击弹层内容时冒泡到遮罩关闭。 */
  stopPropagation() {},

  rerollRandom() {
    void this.chooseRandom()
  },

  onRandomBudgetChange(event: WxPickerEvent) {
    const index = Number(event.detail.value)
    if (!Number.isInteger(index) || index < 0 || index >= this.data.randomBudgetValues.length) return
    this.setData({ randomBudgetIndex: index })
    void this.chooseRandom()
  },

  /**
   * 从符合当前筛选的店铺中随机抽取。
   *
   * 先读取第一页拿到真实总量和可访问页数，再随机取一页，避免在手机上遍历全站；
   * 服务端同时按营业状态和预算收窄，因此每页记录都已经是候选，不需要前端再猜价格。
   */
  async chooseRandom() {
    const sequence = ++this.randomSequence
    const budget = this.data.randomBudgetValues[this.data.randomBudgetIndex]
    const homeBudget = this.data.maxPriceValues[this.data.maxPriceIndex]
    const effectiveBudget =
      budget === null ? homeBudget : homeBudget === null ? budget : Math.min(budget, homeBudget)
    const previousId = this.data.randomResult?.id
    this.setData({ randomLoading: true, randomError: '', randomResult: null })

    const filters = {
      keyword: this.data.keyword,
      category: this.data.activeCategory,
      sort: this.data.sortValues[this.data.sortIndex],
      hideClosed: true,
      restaurantType: this.data.typeValues[this.data.typeIndex],
      diningMode: this.data.diningValues[this.data.diningIndex],
      maxPrice: effectiveBudget,
      pageSize: RANDOM_PAGE_SIZE,
    }

    try {
      const first = await fetchRestaurants({ ...filters, page: 1 })
      if (sequence !== this.randomSequence) return
      const accessiblePages = Math.min(
        first.accessiblePages ?? first.totalPages,
        first.totalPages,
        100,
      )
      let records: RestaurantResponse[] = first.records
      if (accessiblePages > 1) {
        const page = 1 + Math.floor(Math.random() * accessiblePages)
        if (page !== 1) {
          const result = await fetchRestaurants({ ...filters, page })
          if (sequence !== this.randomSequence) return
          records = result.records
        }
      }
      // 目标页可能因并发变化为空；退回第一页即可，不无限重试。
      const pool = records.length ? records : first.records
      const candidates = pool.filter((row) => row.businessStatus === 'OPERATING')
      const selectable =
        candidates.length > 1 ? candidates.filter((row) => row.id !== previousId) : candidates
      const picked = selectable.length
        ? selectable[Math.min(selectable.length - 1, Math.floor(Math.random() * selectable.length))]
        : null
      this.setData({
        randomLoading: false,
        randomCandidateCount: first.total,
        randomResult: picked ? toRestaurantView(picked) : null,
      })
    } catch (reason) {
      if (sequence !== this.randomSequence) return
      this.setData({ randomLoading: false, randomError: toErrorMessage(reason) })
    }
  },

  openRandomResult() {
    const result = this.data.randomResult
    if (!result) return
    this.closeRandom()
    wx.navigateTo({
      url: `/pages/restaurant/detail/index?id=${encodeURIComponent(result.id)}`,
    })
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
    // 页面卸载后不能再用定时器触发请求，也不能让随机推荐响应回写。
    if (this.searchTimer !== null) {
      clearTimeout(this.searchTimer)
      this.searchTimer = null
    }
    this.requestSequence++
    this.randomSequence++
  },
})
