import { ApiError, toErrorMessage } from '../../../shared/api/request'
import { isLoggedIn } from '../../../shared/auth/session'
import { absoluteUrl } from '../../../config/env'
import {
  fetchCanteenWindows,
  fetchFavoriteState,
  fetchRestaurantAlbum,
  fetchRestaurantDetail,
  setFavorite,
} from '../../../modules/restaurant/api'
import {
  toRestaurantView,
  type RestaurantView,
} from '../../../modules/restaurant/types'
import { fetchReviewGroups } from '../../../modules/review/api'
import type { ReviewSort, ReviewView } from '../../../modules/review/types'
import { formatPrice, formatRating } from '../../../shared/lib/format'

type AlbumItem = { id: string; url: string }

type WindowItem = {
  id: string
  name: string
  floorText: string
  priceText: string
  scoreText: string
  reviewCountText: string
  closed: boolean
  coverUrl: string
}

type DetailData = {
  restaurantId: string
  restaurant: RestaurantView | null
  isCanteen: boolean
  album: AlbumItem[]
  albumUrls: string[]
  albumNextCursor: string
  albumLoadingMore: boolean
  windows: WindowItem[]
  reviews: ReviewView[]
  reviewPage: number
  reviewTotalGroups: number
  reviewTotalReviews: number
  reviewTotalPages: number
  reviewSort: ReviewSort
  reviewSortLabels: string[]
  reviewSortValues: ReviewSort[]
  reviewSortIndex: number
  reviewLoading: boolean
  reviewError: string
  hasMoreReviews: boolean
  reviewLoaded: boolean
  loading: boolean
  errorMessage: string
  loggedIn: boolean
  favorited: boolean
  favoriteBusy: boolean
  favoriteHint: string
}

type DetailCustom = {
  requestSequence: number
  /** 登录前点击“写点评”的意图，登录返回后自动打开编辑器。 */
  pendingWriteReview: boolean
  load(): Promise<void>
  loadAlbumMore(): Promise<void>
  loadWindows(): Promise<void>
  loadReviews(reset: boolean): Promise<void>
  loadFavorite(): Promise<void>
  toggleFavorite(): Promise<void>
  writeReview(): void
  openCompose(): void
  login(): void
}

/**
 * 餐厅 / 食堂详情。
 *
 * 资料、正式相册和点评分别读取，任一失败不伪造其他区块的数据；
 * 收藏是唯一写操作，必须登录，并在失败后提示重新读取而不是盲目翻转状态。
 */
Page<DetailData, DetailCustom>({
  data: {
    restaurantId: '',
    restaurant: null,
    isCanteen: false,
    album: [],
    albumUrls: [],
    albumNextCursor: '',
    albumLoadingMore: false,
    windows: [],
    reviews: [],
    reviewPage: 1,
    reviewTotalGroups: 0,
    reviewTotalReviews: 0,
    reviewTotalPages: 0,
    reviewSort: 'DEFAULT',
    reviewSortLabels: ['默认排序', '按时间'],
    reviewSortValues: ['DEFAULT', 'TIME'],
    reviewSortIndex: 0,
    reviewLoading: false,
    reviewError: '',
    hasMoreReviews: false,
    reviewLoaded: false,
    loading: true,
    errorMessage: '',
    loggedIn: false,
    favorited: false,
    favoriteBusy: false,
    favoriteHint: '',
  },

  requestSequence: 0,
  pendingWriteReview: false,

  onLoad(query) {
    const rawId = query.id ? decodeURIComponent(query.id) : ''
    if (!/^[1-9]\d*$/.test(rawId)) {
      this.setData({ loading: false, errorMessage: '店铺编号无效，无法打开详情。' })
      return
    }
    this.setData({ restaurantId: rawId, loggedIn: isLoggedIn() })
    void this.load()
  },

  onShow() {
    const loggedIn = isLoggedIn()
    this.setData({ loggedIn })
    if (this.data.restaurant) {
      void this.loadFavorite()
      // 从发布页返回时重读点评，让新点评与评分及时出现；首次进入由 load 负责。
      if (this.data.reviewLoaded) void this.loadReviews(true)
    }
    if (loggedIn && this.pendingWriteReview) {
      this.pendingWriteReview = false
      this.openCompose()
    }
  },

  async load() {
    const id = this.data.restaurantId
    const sequence = ++this.requestSequence
    this.setData({ loading: true, errorMessage: '', reviewLoaded: false })
    try {
      const [row, albumPage] = await Promise.all([
        fetchRestaurantDetail(id),
        fetchRestaurantAlbum(id),
      ])
      if (sequence !== this.requestSequence) return

      const restaurant = toRestaurantView(row)
      const album = albumPage.records.map((photo) => ({
        id: photo.id,
        // 图片读取接口允许匿名访问，但必须拼上当前环境域名后才能交给 <image>。
        url: absoluteUrl(`/api/restaurants/${encodeURIComponent(id)}/photos/${encodeURIComponent(photo.id)}`),
      }))
      this.setData({
        restaurant,
        isCanteen: row.restaurantType === 'CANTEEN',
        album,
        albumUrls: album.map((item) => item.url),
        albumNextCursor: albumPage.nextCursor ?? '',
        loading: false,
      })
      wx.setNavigationBarTitle({ title: restaurant.name })
      void this.loadReviews(true)
      void this.loadFavorite()
      if (row.restaurantType === 'CANTEEN') void this.loadWindows()
    } catch (reason) {
      if (sequence !== this.requestSequence) return
      this.setData({ loading: false, errorMessage: toErrorMessage(reason) })
    }
  },

  /** 相册后续页只在用户主动点击时读取，避免详情页一次遍历整本相册。 */
  async loadAlbumMore() {
    if (!this.data.albumNextCursor || this.data.albumLoadingMore) return
    const id = this.data.restaurantId
    const sequence = this.requestSequence
    this.setData({ albumLoadingMore: true })
    try {
      const page = await fetchRestaurantAlbum(id, this.data.albumNextCursor)
      if (sequence !== this.requestSequence) return
      const appended = page.records.map((photo) => ({
        id: photo.id,
        url: absoluteUrl(`/api/restaurants/${encodeURIComponent(id)}/photos/${encodeURIComponent(photo.id)}`),
      }))
      const album = this.data.album.concat(appended)
      this.setData({
        album,
        albumUrls: album.map((item) => item.url),
        albumNextCursor: page.nextCursor ?? '',
        albumLoadingMore: false,
      })
    } catch {
      if (sequence === this.requestSequence) this.setData({ albumLoadingMore: false })
    }
  },

  async loadWindows() {
    const sequence = this.requestSequence
    try {
      const rows = await fetchCanteenWindows(this.data.restaurantId)
      if (sequence !== this.requestSequence) return
      const windows = rows.map((row): WindowItem => ({
        id: row.id,
        name: row.name,
        floorText: `${row.floorNumber}层`,
        priceText: formatPrice(row.pricePerPerson, row.priceKind),
        scoreText: formatRating(row.score),
        reviewCountText: `${row.reviewCount} 条点评`,
        closed: row.businessStatus === 'CLOSED',
        coverUrl: row.coverUrl ? absoluteUrl(row.coverUrl) : '',
      }))
      this.setData({ windows })
    } catch {
      // 窗口读取失败不覆盖食堂主体资料；页面不展示伪造窗口。
      if (sequence === this.requestSequence) this.setData({ windows: [] })
    }
  },

  async loadReviews(reset: boolean) {
    if (!this.data.restaurant && reset) return
    if (!reset && (this.data.reviewLoading || !this.data.hasMoreReviews)) return
    const page = reset ? 1 : this.data.reviewPage + 1
    this.setData({ reviewLoading: true, reviewError: '' })
    try {
      const result = await fetchReviewGroups(
        this.data.restaurantId,
        page,
        this.data.reviewSort,
      )
      const reviews = reset ? result.records : this.data.reviews.concat(result.records)
      this.setData({
        reviews,
        reviewPage: result.page,
        reviewTotalGroups: result.totalGroups,
        reviewTotalReviews: result.totalReviews,
        reviewTotalPages: result.totalPages,
        hasMoreReviews: result.page < result.totalPages,
        reviewLoaded: true,
        reviewLoading: false,
      })
    } catch (reason) {
      this.setData({ reviewLoading: false, reviewError: toErrorMessage(reason) })
    }
  },

  async loadFavorite() {
    if (!isLoggedIn()) {
      this.setData({ favorited: false, favoriteHint: '' })
      return
    }
    try {
      const state = await fetchFavoriteState(this.data.restaurantId)
      if (state.restaurantId !== this.data.restaurantId || typeof state.favorited !== 'boolean') {
        this.setData({ favoriteHint: '收藏状态无法确认，请稍后刷新。' })
        return
      }
      this.setData({ favorited: state.favorited, favoriteHint: '' })
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) {
        this.setData({ loggedIn: false, favorited: false, favoriteHint: '' })
        return
      }
      this.setData({ favoriteHint: '收藏状态暂时无法读取。' })
    }
  },

  async toggleFavorite() {
    if (this.data.favoriteBusy || !this.data.restaurant) return
    if (!this.data.loggedIn) {
      this.login()
      return
    }
    this.setData({ favoriteBusy: true, favoriteHint: '' })
    try {
      const active = !this.data.favorited
      const state = await setFavorite(this.data.restaurantId, active)
      if (state.favorited !== active) {
        this.setData({ favoriteHint: '收藏结果无法确认，请重新打开页面核对。' })
        return
      }
      this.setData({ favorited: active })
      wx.showToast({ title: active ? '已收藏' : '已取消收藏', icon: 'success' })
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) {
        this.setData({ loggedIn: false, favorited: false, favoriteHint: '登录已失效，请重新登录。' })
        return
      }
      this.setData({ favoriteHint: toErrorMessage(reason) })
    } finally {
      this.setData({ favoriteBusy: false })
    }
  },

  writeReview() {
    if (!this.data.restaurant) return
    if (this.data.isCanteen) {
      wx.showToast({ title: '窗口点评暂未开放', icon: 'none' })
      return
    }
    if (!isLoggedIn()) {
      this.setData({ loggedIn: false })
      this.pendingWriteReview = true
      this.login()
      return
    }
    this.openCompose()
  },

  /** 评价对象由详情页固定，发布前不允许换店，避免误评到其他店铺。 */
  openCompose() {
    const name = this.data.restaurant ? this.data.restaurant.name : ''
    wx.navigateTo({
      url: `/pages/review/compose/index?id=${encodeURIComponent(this.data.restaurantId)}&name=${encodeURIComponent(name)}`,
    })
  },

  login() {
    const id = this.data.restaurantId
    const redirect = `/pages/restaurant/detail/index?id=${encodeURIComponent(id)}`
    wx.navigateTo({ url: `/pages/login/index?redirect=${encodeURIComponent(redirect)}` })
  },

  previewAlbum(event: WxTapEvent) {
    const current = String(event.currentTarget.dataset.url ?? '')
    if (!current || !this.data.albumUrls.length) return
    wx.previewImage({ urls: this.data.albumUrls, current })
  },

  previewReviewPhoto(event: WxTapEvent) {
    const reviewIndex = Number(event.currentTarget.dataset.reviewIndex)
    const current = String(event.currentTarget.dataset.url ?? '')
    const review = this.data.reviews[reviewIndex]
    if (!review || !current || !review.photoUrls.length) return
    wx.previewImage({ urls: review.photoUrls, current })
  },

  onReviewSortChange(event: WxPickerEvent) {
    const index = Number(event.detail.value)
    if (!Number.isInteger(index) || index < 0 || index >= this.data.reviewSortValues.length) return
    if (index === this.data.reviewSortIndex) return
    this.setData({ reviewSortIndex: index, reviewSort: this.data.reviewSortValues[index] })
    void this.loadReviews(true)
  },

  loadMoreReviews() {
    void this.loadReviews(false)
  },

  retryReviews() {
    void this.loadReviews(true)
  },

  retry() {
    void this.load()
  },

  onShareAppMessage() {
    const name = this.data.restaurant?.name ?? '泥电美食地图'
    return {
      title: `${name} · 泥电美食地图`,
      path: `/pages/restaurant/detail/index?id=${encodeURIComponent(this.data.restaurantId)}`,
    }
  },

  onUnload() {
    // 页面卸载后丢弃所有迟到响应，避免旧页面继续 setData。
    this.requestSequence++
  },
})
