import { fetchMyFavorites, setFavorite } from '../../../modules/restaurant/api'
import type { FavoriteCard } from '../../../modules/restaurant/types'
import { fetchMyReviewGroups } from '../../../modules/review/api'
import type { MyReviewCard } from '../../../modules/review/types'
import { ApiError } from '../../../shared/api/request'
import { isLoggedIn } from '../../../shared/auth/session'

type ContentTab = 'reviews' | 'favorites'

type ContentData = {
  tab: ContentTab
  loggedIn: boolean
  loading: boolean
  loadingMore: boolean
  errorMessage: string
  hint: string
  reviews: MyReviewCard[]
  favorites: FavoriteCard[]
  reviewLoaded: boolean
  favoriteLoaded: boolean
  reviewPage: number
  reviewTotalPages: number
  reviewTotalGroups: number
  reviewTotalReviews: number
  favoritePage: number
  favoriteTotalPages: number
  favoriteTotal: number
  removingId: string
}

type ContentCustom = {
  requestSequence: number
  load(tab: ContentTab, page: number, reset: boolean): Promise<void>
  reload(): Promise<void>
  switchTab(event: WxTapEvent): void
  loadMore(): void
  openRestaurant(event: WxTapEvent): void
  unfavorite(event: WxTapEvent): void
  performUnfavorite(id: string): Promise<void>
  login(): void
  goHome(): void
  currentPath(): string
  handleAuthFailure(reason: unknown): boolean
}

/**
 * 我的内容页同时承载“我的点评”和“我的收藏”。
 *
 * 两类数据都只属于当前登录账号，必须由服务端按认证身份筛选；
 * 页面不提供用户编号参数，也不把公开列表过滤成个人列表。
 */
Page<ContentData, ContentCustom>({
  data: {
    tab: 'reviews',
    loggedIn: false,
    loading: false,
    loadingMore: false,
    errorMessage: '',
    hint: '',
    reviews: [],
    favorites: [],
    reviewLoaded: false,
    favoriteLoaded: false,
    reviewPage: 0,
    reviewTotalPages: 0,
    reviewTotalGroups: 0,
    reviewTotalReviews: 0,
    favoritePage: 0,
    favoriteTotalPages: 0,
    favoriteTotal: 0,
    removingId: '',
  },

  requestSequence: 0,

  onLoad(query) {
    const tab: ContentTab = query.tab === 'favorites' ? 'favorites' : 'reviews'
    const loggedIn = isLoggedIn()
    this.setData({ tab, loggedIn })
    if (loggedIn) void this.load(tab, 1, true)
  },

  onShow() {
    const loggedIn = isLoggedIn()
    if (loggedIn !== this.data.loggedIn) {
      // 账号切换或登录失效后，不能继续展示上一账号的点评与收藏。
      this.requestSequence += 1
      this.setData({
        loggedIn,
        reviews: [],
        favorites: [],
        reviewLoaded: false,
        favoriteLoaded: false,
        loading: false,
        loadingMore: false,
        errorMessage: '',
        hint: '',
      })
    }
    if (!loggedIn) return

    const loaded = this.data.tab === 'reviews' ? this.data.reviewLoaded : this.data.favoriteLoaded
    if (!loaded && !this.data.loading) void this.load(this.data.tab, 1, true)
  },

  onPullDownRefresh() {
    if (!isLoggedIn()) {
      wx.stopPullDownRefresh()
      return
    }
    void this.load(this.data.tab, 1, true).finally(() => wx.stopPullDownRefresh())
  },

  currentPath(): string {
    return `/pages/account/content/index?tab=${this.data.tab}`
  },

  async load(tab: ContentTab, page: number, reset: boolean): Promise<void> {
    if (!isLoggedIn()) {
      this.setData({ loggedIn: false })
      return
    }
    const sequence = ++this.requestSequence
    this.setData({
      loading: reset,
      loadingMore: !reset,
      errorMessage: '',
      // 首屏或刷新时清掉上一次追加失败的提示；追加失败保留给当前列表。
      hint: reset ? '' : this.data.hint,
    })
    try {
      if (tab === 'reviews') {
        const result = await fetchMyReviewGroups(page)
        if (sequence !== this.requestSequence || this.data.tab !== tab) return
        this.setData({
          reviews: reset ? result.records : this.data.reviews.concat(result.records),
          reviewPage: result.page,
          reviewTotalPages: result.totalPages,
          reviewTotalGroups: result.totalGroups,
          reviewTotalReviews: result.totalReviews,
          reviewLoaded: true,
          loading: false,
          loadingMore: false,
          hint: '',
        })
        return
      }

      const result = await fetchMyFavorites(page)
      if (sequence !== this.requestSequence || this.data.tab !== tab) return
      this.setData({
        favorites: reset ? result.records : this.data.favorites.concat(result.records),
        favoritePage: result.page,
        favoriteTotalPages: result.totalPages,
        favoriteTotal: result.total,
        favoriteLoaded: true,
        loading: false,
        loadingMore: false,
        hint: '',
      })
    } catch (reason) {
      if (sequence !== this.requestSequence || this.data.tab !== tab) return
      this.setData({ loading: false, loadingMore: false })
      if (this.handleAuthFailure(reason)) return
      // 追加失败保留已加载内容，只在列表上方提示；首屏失败才进入重试态。
      if (reset) {
        this.setData({ errorMessage: toMessage(reason) })
      } else {
        this.setData({ hint: toMessage(reason) })
      }
    }
  },

  async reload(): Promise<void> {
    await this.load(this.data.tab, 1, true)
  },

  switchTab(event: WxTapEvent): void {
    const tab = event.currentTarget.dataset.tab
    if (tab !== 'reviews' && tab !== 'favorites') return
    if (tab === this.data.tab) return

    // 切换分类时让在途请求失效，避免旧分类结果写进新分类。
    this.requestSequence += 1
    this.setData({
      tab,
      loading: false,
      loadingMore: false,
      errorMessage: '',
      hint: '',
    })
    const loaded = tab === 'reviews' ? this.data.reviewLoaded : this.data.favoriteLoaded
    if (!loaded) void this.load(tab, 1, true)
  },

  loadMore(): void {
    if (this.data.loading || this.data.loadingMore || !this.data.loggedIn) return
    if (this.data.tab === 'reviews') {
      if (this.data.reviewPage >= this.data.reviewTotalPages) return
      void this.load('reviews', this.data.reviewPage + 1, false)
      return
    }
    if (this.data.favoritePage >= this.data.favoriteTotalPages) return
    void this.load('favorites', this.data.favoritePage + 1, false)
  },

  openRestaurant(event: WxTapEvent): void {
    const id = event.currentTarget.dataset.id
    if (typeof id !== 'string' || !/^[1-9]\d*$/.test(id)) return
    if (this.data.tab === 'favorites') {
      const target = this.data.favorites.find((row) => row.restaurantId === id)
      // 失效占位没有可打开的详情，点击卡片不跳转。
      if (!target || !target.available) return
    }
    wx.navigateTo({
      url: `/pages/restaurant/detail/index?id=${encodeURIComponent(id)}`,
    })
  },

  unfavorite(event: WxTapEvent): void {
    const id = event.currentTarget.dataset.id
    if (typeof id !== 'string' || this.data.removingId) return
    const target = this.data.favorites.find((row) => row.restaurantId === id)
    if (!target) return
    wx.showModal({
      title: '取消收藏',
      content: `确定取消收藏“${target.name}”？`,
      confirmText: '取消收藏',
      confirmColor: '#b83a34',
      success: (result) => {
        if (result.confirm) void this.performUnfavorite(id)
      },
    })
  },

  async performUnfavorite(id: string): Promise<void> {
    this.setData({ removingId: id, hint: '' })
    try {
      await setFavorite(id, false)
      // 当前页只剩这一条时回到上一页，避免停留在空页。
      const remaining = this.data.favorites.filter((row) => row.restaurantId !== id).length
      const targetPage = remaining === 0 && this.data.favoritePage > 1
        ? this.data.favoritePage - 1
        : this.data.favoritePage
      wx.showToast({ title: '已取消收藏', icon: 'success' })
      await this.load('favorites', targetPage, true)
    } catch (reason) {
      if (!this.handleAuthFailure(reason)) {
        this.setData({ hint: toMessage(reason) })
      }
    } finally {
      this.setData({ removingId: '' })
    }
  },

  login(): void {
    wx.navigateTo({
      url: `/pages/login/index?redirect=${encodeURIComponent(this.currentPath())}`,
    })
  },

  goHome(): void {
    wx.switchTab({ url: '/pages/home/index' })
  },

  handleAuthFailure(reason: unknown): boolean {
    if (!(reason instanceof ApiError) || reason.status !== 401) return false
    this.requestSequence += 1
    this.setData({
      loggedIn: false,
      reviews: [],
      favorites: [],
      reviewLoaded: false,
      favoriteLoaded: false,
      loading: false,
      loadingMore: false,
      errorMessage: '',
      hint: '',
      removingId: '',
    })
    wx.showToast({ title: '登录状态已失效', icon: 'none' })
    return true
  },
})

/** 页面只展示服务端或模块提供的中文错误，不回显技术细节。 */
function toMessage(reason: unknown): string {
  if (reason instanceof Error && reason.message) return reason.message
  return '读取失败，请稍后重试。'
}
