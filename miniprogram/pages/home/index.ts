import { toErrorMessage } from '../../shared/api/request'
import { isLoggedIn } from '../../shared/auth/session'
import {
  fetchCategories,
  fetchPickerCandidates,
  fetchRestaurants,
  type PickerBudget,
  type PickerDiningMode,
  type PickerMinRating,
  type PickerRange,
} from '../../modules/restaurant/api'
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
  filterOpen: boolean
  hideClosed: boolean
  hasReviews: boolean
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
  randomRangeLabels: string[]
  randomRangeValues: PickerRange[]
  randomRangeIndex: number
  randomDistanceLabels: string[]
  randomDistanceValues: Array<1000 | 3000 | 5000>
  randomDistanceIndex: number
  randomBudgetLabels: string[]
  randomBudgetValues: PickerBudget[]
  randomBudgetIndex: number
  randomRatingLabels: string[]
  randomRatingValues: PickerMinRating[]
  randomRatingIndex: number
  randomCategoryLabels: string[]
  randomCategoryValues: string[]
  randomCategoryIndex: number
  randomDiningLabels: string[]
  randomDiningValues: PickerDiningMode[]
  randomDiningIndex: number
  randomCandidateCount: number
  randomRemainingCount: number
  randomResult: RestaurantView | null
  randomPrevious: RestaurantView | null
}

type HomeCustom = {
  requestSequence: number
  searchTimer: number | null
  randomSequence: number
  /** 本轮已展示与已排除的店铺 ID，服务端不保存会话。 */
  randomSeenIds: string[]
  /** 上一家仅在本轮有效，回看前用 checkIds 复核是否仍符合条件。 */
  randomPreviousView: RestaurantView | null
  /** 用户位置按需获取，避免打开页面就申请定位权限。 */
  randomLocation: { latitude: number; longitude: number } | null
  loadCategories(): Promise<void>
  loadPage(reset: boolean): Promise<void>
  loadMore(): void
  refresh(): Promise<void>
  openSubmission(): void
  openRankings(): void
  toggleFilters(): void
  toggleHideClosed(): void
  toggleHasReviews(): void
  recalcFilterCount(): void
  onPriceChange(event: WxPickerEvent): void
  onTypeChange(event: WxPickerEvent): void
  onDiningChange(event: WxPickerEvent): void
  resetFilters(): void
  openRandom(): void
  closeRandom(): void
  restartRandom(): void
  rerollRandom(): void
  previousRandom(): void
  ensureRandomLocation(): Promise<{ latitude: number; longitude: number }>
  chooseRandom(checkId?: string): Promise<void>
  onRandomRangeChange(event: WxPickerEvent): void
  onRandomDistanceChange(event: WxPickerEvent): void
  onRandomBudgetChange(event: WxPickerEvent): void
  onRandomRatingChange(event: WxPickerEvent): void
  onRandomCategoryChange(event: WxPickerEvent): void
  onRandomDiningChange(event: WxPickerEvent): void
  openRandomResult(): void
  stopPropagation(): void
}

const PAGE_SIZE = 8

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
    hasReviews: false,
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
    randomRangeLabels: ['清水河校区', '沙河校区', '我的位置'],
    randomRangeValues: ['QINGSHUIHE', 'SHAHE', 'USER'],
    randomRangeIndex: 0,
    randomDistanceLabels: ['1km 内', '3km 内', '5km 内'],
    randomDistanceValues: [1000, 3000, 5000],
    randomDistanceIndex: 1,
    randomBudgetLabels: ['不限预算', '≤15元', '≤30元', '≤50元', '≤100元'],
    randomBudgetValues: ['ANY', '15', '30', '50', '100'],
    randomBudgetIndex: 0,
    randomRatingLabels: ['不限评分', '3.0 分以上', '3.5 分以上', '4.0 分以上'],
    randomRatingValues: ['ANY', '3', '3.5', '4'],
    randomRatingIndex: 0,
    randomCategoryLabels: ['全部分类'],
    randomCategoryValues: ['ANY'],
    randomCategoryIndex: 0,
    randomDiningLabels: ['不限方式', '支持堂食', '支持外卖'],
    randomDiningValues: ['ANY', 'DINE_IN', 'DELIVERY'],
    randomDiningIndex: 0,
    randomCandidateCount: 0,
    randomRemainingCount: 0,
    randomResult: null,
    randomPrevious: null,
  },

  requestSequence: 0,
  searchTimer: null,
  randomSequence: 0,
  randomSeenIds: [],
  randomPreviousView: null,
  randomLocation: null,

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

  openRankings() {
    wx.navigateTo({ url: '/pages/rankings/index' })
  },

  /** 分类失败不阻塞餐厅列表；页面会保留“全部”并继续展示可浏览内容。 */
  async loadCategories() {
    try {
      const categories = await fetchCategories()
      const enabled = categories.filter((item) => item.enabled)
      this.setData({
        categories: enabled,
        // 随机选店只接受启用的普通美食分类，因此复用同一份分类字典。
        randomCategoryLabels: ['全部分类'].concat(enabled.map((item) => item.name)),
        randomCategoryValues: ['ANY'].concat(enabled.map((item) => item.code)),
        randomCategoryIndex: 0,
      })
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
        hasReviews: this.data.hasReviews || undefined,
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

  toggleHasReviews() {
    this.setData({ hasReviews: !this.data.hasReviews })
    this.recalcFilterCount()
    void this.loadPage(true)
  },

  /** 面板内三个下拉和“只看有点评”合并计数，隐藏停业是独立开关不计数。 */
  recalcFilterCount() {
    this.setData({
      activeFilterCount:
        (this.data.maxPriceIndex > 0 ? 1 : 0) +
        (this.data.typeIndex > 0 ? 1 : 0) +
        (this.data.diningIndex > 0 ? 1 : 0) +
        (this.data.hasReviews ? 1 : 0),
    })
  },

  onPriceChange(event: WxPickerEvent) {
    const index = Number(event.detail.value)
    if (!Number.isInteger(index) || index < 0 || index >= this.data.maxPriceValues.length) return
    if (index === this.data.maxPriceIndex) return
    this.setData({ maxPriceIndex: index })
    this.recalcFilterCount()
    void this.loadPage(true)
  },

  onTypeChange(event: WxPickerEvent) {
    const index = Number(event.detail.value)
    if (!Number.isInteger(index) || index < 0 || index >= this.data.typeValues.length) return
    if (index === this.data.typeIndex) return
    this.setData({ typeIndex: index })
    this.recalcFilterCount()
    void this.loadPage(true)
  },

  onDiningChange(event: WxPickerEvent) {
    const index = Number(event.detail.value)
    if (!Number.isInteger(index) || index < 0 || index >= this.data.diningValues.length) return
    if (index === this.data.diningIndex) return
    this.setData({ diningIndex: index })
    this.recalcFilterCount()
    void this.loadPage(true)
  },

  resetFilters() {
    this.setData({
      hideClosed: true,
      hasReviews: false,
      maxPriceIndex: 0,
      typeIndex: 0,
      diningIndex: 0,
      activeFilterCount: 0,
      filterOpen: false,
    })
    void this.loadPage(true)
  },

  /** 打开随机推荐；保留本轮已看过的店铺，避免每次打开都从零开始。 */
  openRandom() {
    this.setData({ randomVisible: true, randomError: '' })
    if (!this.data.randomResult && !this.data.randomLoading) {
      void this.chooseRandom()
    }
  },

  closeRandom() {
    // 关闭弹层后迟到的响应不能再把结果写回页面。
    this.randomSequence++
    this.setData({ randomVisible: false, randomLoading: false, randomError: '' })
  },

  /** 阻止点击弹层内容时冒泡到遮罩关闭。 */
  stopPropagation() {},

  /** 重新开始一轮：清空已看店铺和上一家，但保留当前筛选条件。 */
  restartRandom() {
    this.randomSeenIds = []
    this.randomPreviousView = null
    this.setData({ randomResult: null, randomPrevious: null, randomError: '' })
    void this.chooseRandom()
  },

  rerollRandom() {
    // 换一家时把当前结果保留为“上一家”，方便回看。
    this.randomPreviousView = this.data.randomResult
    void this.chooseRandom()
  },

  previousRandom() {
    if (!this.randomPreviousView) return
    void this.chooseRandom(this.randomPreviousView.id)
  },

  onRandomRangeChange(event: WxPickerEvent) {
    const index = Number(event.detail.value)
    if (!Number.isInteger(index) || index < 0 || index >= this.data.randomRangeValues.length) return
    this.randomSeenIds = []
    this.randomPreviousView = null
    this.setData({
      randomRangeIndex: index,
      randomPrevious: null,
      randomResult: null,
    })
    void this.chooseRandom()
  },

  onRandomDistanceChange(event: WxPickerEvent) {
    const index = Number(event.detail.value)
    if (!Number.isInteger(index) || index < 0 || index >= this.data.randomDistanceValues.length) {
      return
    }
    this.randomSeenIds = []
    this.randomPreviousView = null
    this.setData({
      randomDistanceIndex: index,
      randomPrevious: null,
      randomResult: null,
    })
    void this.chooseRandom()
  },

  onRandomBudgetChange(event: WxPickerEvent) {
    const index = Number(event.detail.value)
    if (!Number.isInteger(index) || index < 0 || index >= this.data.randomBudgetValues.length) return
    this.randomSeenIds = []
    this.randomPreviousView = null
    this.setData({
      randomBudgetIndex: index,
      randomPrevious: null,
      randomResult: null,
    })
    void this.chooseRandom()
  },

  onRandomRatingChange(event: WxPickerEvent) {
    const index = Number(event.detail.value)
    if (!Number.isInteger(index) || index < 0 || index >= this.data.randomRatingValues.length) return
    this.randomSeenIds = []
    this.randomPreviousView = null
    this.setData({
      randomRatingIndex: index,
      randomPrevious: null,
      randomResult: null,
    })
    void this.chooseRandom()
  },

  onRandomCategoryChange(event: WxPickerEvent) {
    const index = Number(event.detail.value)
    if (!Number.isInteger(index) || index < 0 || index >= this.data.randomCategoryValues.length) {
      return
    }
    this.randomSeenIds = []
    this.randomPreviousView = null
    this.setData({
      randomCategoryIndex: index,
      randomPrevious: null,
      randomResult: null,
    })
    void this.chooseRandom()
  },

  onRandomDiningChange(event: WxPickerEvent) {
    const index = Number(event.detail.value)
    if (!Number.isInteger(index) || index < 0 || index >= this.data.randomDiningValues.length) return
    this.randomSeenIds = []
    this.randomPreviousView = null
    this.setData({
      randomDiningIndex: index,
      randomPrevious: null,
      randomResult: null,
    })
    void this.chooseRandom()
  },

  /** 仅在用户明确选择“我的位置”时申请定位，失败时提示改用校区范围。 */
  ensureRandomLocation(): Promise<{ latitude: number; longitude: number }> {
    if (this.randomLocation) return Promise.resolve(this.randomLocation)
    return new Promise((resolve, reject) => {
      wx.getLocation({
        type: 'gcj02',
        isHighAccuracy: true,
        success: (result) => {
          const latitude = Number(result.latitude)
          const longitude = Number(result.longitude)
          if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) {
            reject(new Error('定位结果无法确认，请改用校区范围。'))
            return
          }
          this.randomLocation = { latitude, longitude }
          resolve(this.randomLocation)
        },
        fail: () => {
          reject(new Error('无法获取我的位置，请允许定位或改用校区范围。'))
        },
      })
    })
  },

  /**
   * 通过服务端随机选店接口抽取。
   *
   * 服务端在完整合格集合上均匀不放回抽取至多 5 家，客户端只取第一张卡片；
   * 同批返回的其余店铺也进入排除列表，配合本轮历史避免重复。
   * 传入 checkId 时进入复核模式，只确认“上一家”是否仍符合当前条件。
   */
  async chooseRandom(checkId = '') {
    const sequence = ++this.randomSequence
    const range = this.data.randomRangeValues[this.data.randomRangeIndex]
    const budget = this.data.randomBudgetValues[this.data.randomBudgetIndex]
    const minRating = this.data.randomRatingValues[this.data.randomRatingIndex]
    const category = this.data.randomCategoryValues[this.data.randomCategoryIndex]
    const diningMode = this.data.randomDiningValues[this.data.randomDiningIndex]
    if (!range || !budget || !minRating || !category || !diningMode) {
      this.setData({ randomLoading: false, randomError: '选店条件无法确认，请重新选择。' })
      return
    }
    const previous = this.randomPreviousView
    this.setData({
      randomLoading: true,
      randomError: '',
      ...(checkId ? {} : { randomResult: null }),
    })

    try {
      let location: { latitude: number; longitude: number } | undefined
      if (range === 'USER') {
        location = await this.ensureRandomLocation()
        if (sequence !== this.randomSequence) return
      }
      const result = await fetchPickerCandidates({
        range,
        distanceMeters: this.data.randomDistanceValues[this.data.randomDistanceIndex],
        budget,
        minRating,
        category,
        diningMode,
        latitude: location?.latitude,
        longitude: location?.longitude,
        excludeIds: checkId ? [] : this.randomSeenIds,
        checkIds: checkId ? [checkId] : [],
      })
      if (sequence !== this.randomSequence) return

      if (checkId) {
        const found = result.records.find((row) => row.id === checkId)
        if (!found) {
          this.setData({
            randomLoading: false,
            randomError: '上一家已不符合当前条件，请换一家。',
          })
          return
        }
        this.randomPreviousView = this.data.randomResult
        this.setData({
          randomLoading: false,
          // 回看上一家后，新结果显示的“上一家”应是刚才离开的那家，而不是自己。
          randomPrevious: this.data.randomResult,
          randomResult: toRestaurantView(found),
        })
        return
      }

      const picked = result.records[0]
      if (picked) {
        const batchIds = result.records.map((row) => row.id)
        const seen = new Set<string>(this.randomSeenIds)
        batchIds.forEach((id) => seen.add(id))
        // 服务端上限为 50000 个 ID，超出时丢弃最早记录，仍保证本轮近期不重复。
        this.randomSeenIds = Array.from(seen).slice(-50000)
      }
      this.setData({
        randomLoading: false,
        randomCandidateCount: result.matchedCount,
        randomRemainingCount: result.remainingCount,
        randomPrevious: previous,
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
