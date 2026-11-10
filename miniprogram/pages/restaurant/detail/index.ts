import { ApiError, toErrorMessage } from '../../../shared/api/request'
import { getCurrentUser, isLoggedIn } from '../../../shared/auth/session'
import { absoluteUrl } from '../../../config/env'
import {
  fetchCanteenWindowDetail,
  fetchCanteenWindowPhotos,
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
import { fetchReviewGroups, fetchWindowReviewGroups } from '../../../modules/review/api'
import {
  REPORT_REASONS,
  createReply,
  deleteReply,
  fetchLikeStates,
  fetchReplyPage,
  setContentLike,
  submitContentReport,
  type LikeTargetType,
  type ReplyView,
  type ReportReason,
} from '../../../modules/review/interactions'
import type { ReviewSort, ReviewView } from '../../../modules/review/types'
import { createRequestId } from '../../../shared/lib/id'
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

/** 回复卡片在原始回复上补充点赞、删除与举报所需的页面状态。 */
type ReplyCardView = ReplyView & {
  likeCount: number
  likeKnown: boolean
  likeAvailable: boolean
  liked: boolean
  likeBusy: boolean
  canLike: boolean
  canDelete: boolean
  canReport: boolean
}

/** 点评卡片在只读视图上增加点赞、回复展开与回复编辑状态。 */
type ReviewCardView = ReviewView & {
  canLike: boolean
  canReport: boolean
  likeCount: number
  likeKnown: boolean
  likeAvailable: boolean
  liked: boolean
  likeBusy: boolean
  replies: ReplyCardView[]
  repliesOpen: boolean
  repliesLoaded: boolean
  repliesLoading: boolean
  repliesHasMore: boolean
  repliesCursor: string
  repliesError: string
  replyDraft: string
  replyDraftLength: number
  replyComposerOpen: boolean
  replySubmitting: boolean
  replyError: string
}

const MAX_REPLY_BODY = 200
const MAX_REPORT_DETAIL = 200

/** 与后端一致的码点计数：emoji 等代理对按一个字计算。 */
function textLength(value: string): number {
  return Array.from(value).length
}

function toReplyCard(reply: ReplyView, currentUserId: string, loggedIn: boolean): ReplyCardView {
  const isSelf = reply.authorId !== '' && reply.authorId === currentUserId
  return {
    ...reply,
    likeCount: 0,
    likeKnown: false,
    likeAvailable: false,
    liked: false,
    likeBusy: false,
    canLike: !isSelf,
    canDelete: isSelf,
    canReport: loggedIn && !isSelf,
  }
}

function toReviewCard(review: ReviewView, currentUserId: string, loggedIn: boolean): ReviewCardView {
  const isSelf = currentUserId !== '' && review.authorId === currentUserId
  return {
    ...review,
    canLike: !isSelf,
    canReport: loggedIn && !isSelf,
    likeCount: 0,
    likeKnown: false,
    likeAvailable: false,
    liked: false,
    likeBusy: false,
    replies: [],
    repliesOpen: false,
    repliesLoaded: false,
    repliesLoading: false,
    repliesHasMore: false,
    repliesCursor: '',
    repliesError: '',
    replyDraft: '',
    replyDraftLength: 0,
    replyComposerOpen: false,
    replySubmitting: false,
    replyError: '',
  }
}

type DetailData = {
  restaurantId: string
  restaurant: RestaurantView | null
  isCanteen: boolean
  /** 独立食堂窗口复用本页的点评与互动，但资料、相册和收藏语义不同。 */
  isWindow: boolean
  windowDescription: string
  album: AlbumItem[]
  albumUrls: string[]
  albumEmptyText: string
  albumNextCursor: string
  albumLoadingMore: boolean
  windows: WindowItem[]
  reviews: ReviewCardView[]
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
  currentUserId: string
  interactionHint: string
  favorited: boolean
  favoriteBusy: boolean
  favoriteHint: string
  reportOpen: boolean
  reportTargetType: LikeTargetType
  reportTargetId: string
  reportTargetLabel: string
  reportReasonLabels: string[]
  reportReasonIndex: number
  reportDetail: string
  reportDetailLength: number
  reportSubmitting: boolean
  reportError: string
}

type DetailCustom = {
  requestSequence: number
  /** 登录前点击“写点评”的意图，登录返回后自动打开编辑器。 */
  pendingWriteReview: boolean
  /** 回复请求号按点评缓存；正文变化后丢弃，保证幂等重试只在内容一致时生效。 */
  replyRequestIds: Record<string, string>
  load(): Promise<void>
  loadWindow(id: string, sequence: number): Promise<void>
  loadAlbumMore(): Promise<void>
  loadWindows(): Promise<void>
  loadReviews(reset: boolean): Promise<void>
  loadFavorite(): Promise<void>
  toggleFavorite(): Promise<void>
  writeReview(): void
  openCompose(): void
  openCorrection(): void
  login(): void
  loadReviewLikes(reviews: ReviewCardView[]): Promise<void>
  loadReplyLikes(reviewId: string, replies: ReplyCardView[]): Promise<void>
  patchReviewById(reviewId: string, patch: Partial<ReviewCardView>): void
  patchReplyById(reviewId: string, replyId: string, patch: Partial<ReplyCardView>): void
  handleAuthFailure(reason: unknown): boolean
  handleInteractionError(reason: unknown): void
  commitReviewLike(review: ReviewCardView): Promise<void>
  commitReplyLike(review: ReviewCardView, reply: ReplyCardView): Promise<void>
  confirmDeleteReply(reviewId: string, replyId: string): Promise<void>
  toggleReviewLike(event: WxTapEvent): void
  toggleReplies(event: WxTapEvent): void
  retryReplies(event: WxTapEvent): void
  loadMoreReplies(event: WxTapEvent): void
  loadReplies(reviewId: string, more: boolean): Promise<void>
  openReplyComposer(event: WxTapEvent): void
  onReplyInput(event: WxInputEvent & WxTapEvent): void
  submitReply(event: WxTapEvent): Promise<void>
  toggleReplyLike(event: WxTapEvent): void
  deleteReply(event: WxTapEvent): void
  openReport(event: WxTapEvent): void
  closeReport(): void
  stopPropagation(): void
  onReportReasonChange(event: WxPickerEvent): void
  onReportDetailInput(event: WxInputEvent): void
  submitReport(): Promise<void>
  openWindow(event: WxTapEvent): void
  /** 点击点评作者进入公开主页，本人作者同样可查看自己的公开资料。 */
  openAuthor(event: WxTapEvent): void
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
    isWindow: false,
    windowDescription: '',
    album: [],
    albumUrls: [],
    albumEmptyText: '暂无店铺照片',
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
    currentUserId: '',
    interactionHint: '',
    favorited: false,
    favoriteBusy: false,
    favoriteHint: '',
    reportOpen: false,
    reportTargetType: 'REVIEW',
    reportTargetId: '',
    reportTargetLabel: '',
    reportReasonLabels: REPORT_REASONS.map((item) => item.label),
    reportReasonIndex: 0,
    reportDetail: '',
    reportDetailLength: 0,
    reportSubmitting: false,
    reportError: '',
  },

  requestSequence: 0,
  pendingWriteReview: false,
  replyRequestIds: {},

  onLoad(query) {
    /*
     * 独立窗口通过 windowId 复用本页：点评、回复、点赞与举报都以点评编号为入口，
     * 与所属店铺类型无关；资料、相册与收藏则按窗口分支读取。
     */
    const windowId = query.windowId ? decodeURIComponent(query.windowId) : ''
    if (windowId) {
      if (!/^[1-9]\d*$/.test(windowId)) {
        this.setData({ loading: false, errorMessage: '窗口编号无效，无法打开详情。' })
        return
      }
      this.setData({
        restaurantId: windowId,
        isWindow: true,
        loggedIn: isLoggedIn(),
        currentUserId: getCurrentUser()?.id.toString() ?? '',
      })
      void this.load()
      return
    }
    const rawId = query.id ? decodeURIComponent(query.id) : ''
    if (!/^[1-9]\d*$/.test(rawId)) {
      this.setData({ loading: false, errorMessage: '店铺编号无效，无法打开详情。' })
      return
    }
    this.setData({
      restaurantId: rawId,
      loggedIn: isLoggedIn(),
      currentUserId: getCurrentUser()?.id.toString() ?? '',
    })
    void this.load()
  },

  onShow() {
    const loggedIn = isLoggedIn()
    this.setData({
      loggedIn,
      // 本人内容不允许点赞或举报，重新进入时同步最新账号身份。
      currentUserId: getCurrentUser()?.id.toString() ?? '',
    })
    if (this.data.restaurant) {
      // 窗口不支持收藏，不请求收藏状态；点评仍按原逻辑刷新。
      if (!this.data.isWindow) void this.loadFavorite()
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
    if (this.data.isWindow) {
      await this.loadWindow(id, sequence)
      return
    }
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

  /**
   * 窗口资料与相册独立读取。
   *
   * 相册最多 5 张且读取失败不能阻止资料展示；失败时明确提示，不伪造空相册为真实结果。
   * 窗口本身不支持收藏，也不在这里请求收藏状态。
   */
  async loadWindow(id: string, sequence: number) {
    try {
      const detail = await fetchCanteenWindowDetail(id)
      if (sequence !== this.requestSequence) return
      let album: AlbumItem[] = []
      let albumEmptyText = '暂无窗口照片'
      try {
        const photos = await fetchCanteenWindowPhotos(id)
        if (sequence !== this.requestSequence) return
        album = photos.map((photo) => ({ id: photo.id, url: photo.url }))
      } catch {
        if (sequence !== this.requestSequence) return
        albumEmptyText = '窗口相册暂时无法读取。'
      }
      this.setData({
        restaurant: detail.view,
        isCanteen: false,
        windowDescription: detail.description,
        album,
        albumUrls: album.map((item) => item.url),
        albumEmptyText,
        albumNextCursor: '',
        loading: false,
      })
      wx.setNavigationBarTitle({ title: detail.view.name })
      void this.loadReviews(true)
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
      const result = this.data.isWindow
        ? await fetchWindowReviewGroups(this.data.restaurantId, page, this.data.reviewSort)
        : await fetchReviewGroups(this.data.restaurantId, page, this.data.reviewSort)
      const records = result.records.map((review) =>
        toReviewCard(review, this.data.currentUserId, this.data.loggedIn),
      )
      const reviews = reset ? records : this.data.reviews.concat(records)
      this.setData({
        reviews,
        reviewPage: result.page,
        reviewTotalGroups: result.totalGroups,
        reviewTotalReviews: result.totalReviews,
        reviewTotalPages: result.totalPages,
        hasMoreReviews: result.page < result.totalPages,
        reviewLoaded: true,
        reviewLoading: false,
        // 重新读取点评时清理上一条互动错误，避免旧提示挂在新列表上。
        interactionHint: reset ? '' : this.data.interactionHint,
      })
      // 点赞状态独立读取，失败时保持未知，不影响点评正文展示。
      void this.loadReviewLikes(records)
    } catch (reason) {
      this.setData({ reviewLoading: false, reviewError: toErrorMessage(reason) })
    }
  },

  async loadFavorite() {
    // 窗口没有收藏概念；即使误调用也不能读取餐厅收藏接口。
    if (this.data.isWindow) return
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
    if (this.data.isWindow) return
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
    // 食堂主体本身不接收点评，需要进入具体窗口；独立窗口详情允许直接发布。
    if (this.data.isCanteen && !this.data.isWindow) {
      wx.showToast({ title: '请选择窗口后再写点评', icon: 'none' })
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

  /**
   * 打开纠错页并带上目标编号。
   *
   * 独立窗口没有对应纠错入口；登录由纠错页自行处理，
   * 保持当前详情页不变，登录后可回到同一目标继续填写。
   */
  openCorrection() {
    if (!this.data.restaurant || this.data.isWindow) return
    wx.navigateTo({
      url: `/pages/submission/index?id=${encodeURIComponent(this.data.restaurantId)}`,
    })
  },

  /**
   * 评价对象由详情页固定，发布前不允许换店，避免误评到其他店铺。
   *
   * 窗口与餐厅编号可能相同，必须分别使用 windowId 和 id 参数，
   * 否则会把窗口点评发布到同号餐厅。
   */
  openCompose() {
    const name = this.data.restaurant ? this.data.restaurant.name : ''
    const target = this.data.isWindow
      ? `windowId=${encodeURIComponent(this.data.restaurantId)}`
      : `id=${encodeURIComponent(this.data.restaurantId)}`
    wx.navigateTo({
      url: `/pages/review/compose/index?${target}&name=${encodeURIComponent(name)}`,
    })
  },

  login() {
    const redirect = this.data.isWindow
      ? `/pages/restaurant/detail/index?windowId=${encodeURIComponent(this.data.restaurantId)}`
      : `/pages/restaurant/detail/index?id=${encodeURIComponent(this.data.restaurantId)}`
    wx.navigateTo({ url: `/pages/login/index?redirect=${encodeURIComponent(redirect)}` })
  },

  /** 401 只切换当前页面登录态，保留已加载的公开点评内容。 */
  handleAuthFailure(reason: unknown): boolean {
    if (reason instanceof ApiError && reason.status === 401) {
      this.setData({ loggedIn: false, interactionHint: '登录已失效，请重新登录后再操作。' })
      return true
    }
    return false
  },

  handleInteractionError(reason: unknown): void {
    if (this.handleAuthFailure(reason)) return
    this.setData({ interactionHint: toErrorMessage(reason) })
  },

  /**
   * 批量读取点评点赞状态。
   *
   * 新加载的一页最多 8 条，一次批量请求即可；状态失败的条目保持“未知”，
   * 不显示 0 或未点赞，避免把查询失败解释成真实互动数据。
   */
  async loadReviewLikes(rows: ReviewCardView[]): Promise<void> {
    const ids = rows.map((row) => row.id)
    if (!ids.length) return
    try {
      const states = await fetchLikeStates('REVIEW', ids)
      const reviews = this.data.reviews.slice()
      let changed = false
      reviews.forEach((row, index) => {
        const state = states.get(row.id)
        if (!state) return
        reviews[index] = {
          ...row,
          likeCount: state.count,
          likeKnown: true,
          likeAvailable: state.available,
          liked: state.liked,
        }
        changed = true
      })
      if (changed) this.setData({ reviews })
    } catch (reason) {
      this.handleAuthFailure(reason)
    }
  },

  /** 回复点赞同样批量读取；按回复编号回填，避免列表重排后写到错误卡片。 */
  async loadReplyLikes(reviewId: string, replies: ReplyCardView[]): Promise<void> {
    const ids = replies.map((reply) => reply.id)
    if (!ids.length) return
    try {
      const states = await fetchLikeStates('REPLY', ids)
      const review = this.data.reviews.find((row) => row.id === reviewId)
      if (!review) return
      let changed = false
      const next = review.replies.map((reply) => {
        const state = states.get(reply.id)
        if (!state) return reply
        changed = true
        return {
          ...reply,
          likeCount: state.count,
          likeKnown: true,
          likeAvailable: state.available,
          liked: state.liked,
        }
      })
      if (changed) this.patchReviewById(reviewId, { replies: next })
    } catch (reason) {
      this.handleAuthFailure(reason)
    }
  },

  patchReviewById(reviewId: string, patch: Partial<ReviewCardView>): void {
    const reviews = this.data.reviews.slice()
    const index = reviews.findIndex((row) => row.id === reviewId)
    if (index < 0) return
    reviews[index] = { ...reviews[index], ...patch }
    this.setData({ reviews })
  },

  patchReplyById(reviewId: string, replyId: string, patch: Partial<ReplyCardView>): void {
    const reviews = this.data.reviews.slice()
    const reviewIndex = reviews.findIndex((row) => row.id === reviewId)
    if (reviewIndex < 0) return
    const replies = reviews[reviewIndex].replies.slice()
    const replyIndex = replies.findIndex((row) => row.id === replyId)
    if (replyIndex < 0) return
    replies[replyIndex] = { ...replies[replyIndex], ...patch }
    reviews[reviewIndex] = { ...reviews[reviewIndex], replies }
    this.setData({ reviews })
  },

  toggleReviewLike(event: WxTapEvent): void {
    const index = Number(event.currentTarget.dataset.index)
    const review = this.data.reviews[index]
    if (!review || review.likeBusy) return
    if (!this.data.loggedIn) {
      this.login()
      return
    }
    if (!review.canLike) {
      wx.showToast({ title: '不能给自己点赞', icon: 'none' })
      return
    }
    if (review.likeKnown && !review.likeAvailable) {
      wx.showToast({ title: '内容已失效或不可访问', icon: 'none' })
      return
    }
    void this.commitReviewLike(review)
  },

  async commitReviewLike(review: ReviewCardView): Promise<void> {
    this.patchReviewById(review.id, { likeBusy: true })
    try {
      const state = await setContentLike('REVIEW', review.id, !review.liked)
      this.patchReviewById(review.id, {
        likeCount: state.count,
        likeKnown: true,
        likeAvailable: state.available,
        liked: state.liked,
        likeBusy: false,
      })
      this.setData({ interactionHint: '' })
    } catch (reason) {
      this.patchReviewById(review.id, { likeBusy: false })
      this.handleInteractionError(reason)
    }
  },

  toggleReplies(event: WxTapEvent): void {
    const index = Number(event.currentTarget.dataset.index)
    const review = this.data.reviews[index]
    if (!review) return
    if (review.repliesOpen) {
      this.patchReviewById(review.id, { repliesOpen: false })
      return
    }
    this.patchReviewById(review.id, { repliesOpen: true })
    if (!review.repliesLoaded) void this.loadReplies(review.id, false)
  },

  retryReplies(event: WxTapEvent): void {
    const index = Number(event.currentTarget.dataset.index)
    const review = this.data.reviews[index]
    if (review) void this.loadReplies(review.id, false)
  },

  loadMoreReplies(event: WxTapEvent): void {
    const index = Number(event.currentTarget.dataset.index)
    const review = this.data.reviews[index]
    if (review) void this.loadReplies(review.id, true)
  },

  /**
   * 读取回复分页。
   *
   * 首次或刷新时从游标头开始并覆盖原列表；更多回复按服务端 nextCursor 追加。
   * 服务端返回的游标原样带回，不在前端解析或构造分页位置。
   */
  async loadReplies(reviewId: string, more: boolean): Promise<void> {
    const review = this.data.reviews.find((row) => row.id === reviewId)
    if (!review || review.repliesLoading) return
    if (more && !review.repliesHasMore) return
    const cursor = more ? review.repliesCursor : ''
    this.patchReviewById(reviewId, { repliesLoading: true, repliesError: '' })
    try {
      const page = await fetchReplyPage(reviewId, cursor)
      const current = this.data.reviews.find((row) => row.id === reviewId)
      if (!current) return
      const added = page.records.map((reply) =>
        toReplyCard(reply, this.data.currentUserId, this.data.loggedIn),
      )
      const replies = more ? current.replies.concat(added) : added
      this.patchReviewById(reviewId, {
        replies,
        repliesLoaded: true,
        repliesLoading: false,
        repliesHasMore: page.hasMore,
        repliesCursor: page.nextCursor,
      })
      void this.loadReplyLikes(reviewId, added)
    } catch (reason) {
      if (this.handleAuthFailure(reason)) {
        this.patchReviewById(reviewId, { repliesLoading: false })
        return
      }
      this.patchReviewById(reviewId, {
        repliesLoading: false,
        repliesError: toErrorMessage(reason),
      })
    }
  },

  openReplyComposer(event: WxTapEvent): void {
    const index = Number(event.currentTarget.dataset.index)
    const review = this.data.reviews[index]
    if (!review) return
    if (!this.data.loggedIn) {
      this.login()
      return
    }
    this.patchReviewById(review.id, { replyComposerOpen: true, replyError: '' })
  },

  onReplyInput(event: WxInputEvent & WxTapEvent): void {
    const index = Number(event.currentTarget.dataset.index)
    const review = this.data.reviews[index]
    if (!review) return
    const draft = event.detail.value ?? ''
    // 正文变化后旧请求号不能再用，否则服务端会按不同内容判定冲突。
    delete this.replyRequestIds[review.id]
    this.patchReviewById(review.id, {
      replyDraft: draft,
      replyDraftLength: textLength(draft),
      replyError: '',
    })
  },

  async submitReply(event: WxTapEvent): Promise<void> {
    const index = Number(event.currentTarget.dataset.index)
    const review = this.data.reviews[index]
    if (!review || review.replySubmitting) return
    if (!this.data.loggedIn) {
      this.login()
      return
    }
    const body = review.replyDraft.trim()
    const length = textLength(body)
    if (length < 1) {
      this.patchReviewById(review.id, { replyError: '请输入回复内容。' })
      return
    }
    if (length > MAX_REPLY_BODY) {
      this.patchReviewById(review.id, { replyError: `回复最多 ${MAX_REPLY_BODY} 个字。` })
      return
    }

    let requestId = this.replyRequestIds[review.id]
    if (!requestId) {
      requestId = createRequestId()
      this.replyRequestIds[review.id] = requestId
    }
    this.patchReviewById(review.id, { replySubmitting: true, replyError: '' })
    try {
      await createReply(review.id, body, requestId)
      delete this.replyRequestIds[review.id]
      this.patchReviewById(review.id, {
        replyDraft: '',
        replyDraftLength: 0,
        replyComposerOpen: false,
        replySubmitting: false,
      })
      wx.showToast({ title: '回复已发布', icon: 'success' })
      // 重新读取第一页，确保回复顺序与总数以服务端为准。
      void this.loadReplies(review.id, false)
    } catch (reason) {
      this.patchReviewById(review.id, {
        replySubmitting: false,
        replyError: toErrorMessage(reason),
      })
      this.handleAuthFailure(reason)
    }
  },

  toggleReplyLike(event: WxTapEvent): void {
    const reviewIndex = Number(event.currentTarget.dataset.reviewIndex)
    const replyIndex = Number(event.currentTarget.dataset.replyIndex)
    const review = this.data.reviews[reviewIndex]
    const reply = review ? review.replies[replyIndex] : undefined
    if (!review || !reply || reply.likeBusy) return
    if (!this.data.loggedIn) {
      this.login()
      return
    }
    if (!reply.canLike) {
      wx.showToast({ title: '不能给自己点赞', icon: 'none' })
      return
    }
    if (reply.likeKnown && !reply.likeAvailable) {
      wx.showToast({ title: '回复已失效或不可访问', icon: 'none' })
      return
    }
    void this.commitReplyLike(review, reply)
  },

  async commitReplyLike(review: ReviewCardView, reply: ReplyCardView): Promise<void> {
    this.patchReplyById(review.id, reply.id, { likeBusy: true })
    try {
      const state = await setContentLike('REPLY', reply.id, !reply.liked)
      this.patchReplyById(review.id, reply.id, {
        likeCount: state.count,
        likeKnown: true,
        likeAvailable: state.available,
        liked: state.liked,
        likeBusy: false,
      })
      this.setData({ interactionHint: '' })
    } catch (reason) {
      this.patchReplyById(review.id, reply.id, { likeBusy: false })
      this.handleInteractionError(reason)
    }
  },

  deleteReply(event: WxTapEvent): void {
    const reviewIndex = Number(event.currentTarget.dataset.reviewIndex)
    const replyIndex = Number(event.currentTarget.dataset.replyIndex)
    const review = this.data.reviews[reviewIndex]
    const reply = review ? review.replies[replyIndex] : undefined
    if (!review || !reply || !reply.canDelete) return
    if (!this.data.loggedIn) {
      this.login()
      return
    }
    wx.showModal({
      title: '删除回复',
      content: '删除后不能恢复，确定继续吗？',
      confirmText: '删除',
      confirmColor: '#b83a34',
      success: (result) => {
        if (!result.confirm) return
        void this.confirmDeleteReply(review.id, reply.id)
      },
    })
  },

  async confirmDeleteReply(reviewId: string, replyId: string): Promise<void> {
    try {
      await deleteReply(replyId)
      const review = this.data.reviews.find((row) => row.id === reviewId)
      if (review) {
        this.patchReviewById(reviewId, {
          replies: review.replies.filter((row) => row.id !== replyId),
        })
      }
      wx.showToast({ title: '回复已删除', icon: 'success' })
      // 删除可能移除当前分页锚点，旧游标会失效；从第一页重新读取保证还能继续翻页。
      void this.loadReplies(reviewId, false)
    } catch (reason) {
      if (this.handleAuthFailure(reason)) return
      this.setData({ interactionHint: toErrorMessage(reason) })
    }
  },

  /**
   * 打开举报面板。
   *
   * 目标类型和编号在点击时固定，提交期间不能切换，避免把 A 的举报写到 B。
   * 本人内容在本地直接拒绝，服务端仍会按归属再次校验。
   */
  openReport(event: WxTapEvent): void {
    const reviewIndex = Number(event.currentTarget.dataset.reviewIndex)
    const replyIndexRaw = event.currentTarget.dataset.replyIndex
    const review = this.data.reviews[reviewIndex]
    if (!review) return
    if (!this.data.loggedIn) {
      this.login()
      return
    }

    let targetType: LikeTargetType = 'REVIEW'
    let targetId = review.id
    let authorId = review.authorId
    let targetLabel = '该点评'
    if (replyIndexRaw !== undefined && replyIndexRaw !== '') {
      const reply = review.replies[Number(replyIndexRaw)]
      if (!reply) return
      targetType = 'REPLY'
      targetId = reply.id
      authorId = reply.authorId
      targetLabel = '该回复'
    }
    if (authorId && authorId === this.data.currentUserId) {
      wx.showToast({ title: '不能举报自己的内容', icon: 'none' })
      return
    }
    this.setData({
      reportOpen: true,
      reportTargetType: targetType,
      reportTargetId: targetId,
      reportTargetLabel: targetLabel,
      reportReasonIndex: 0,
      reportDetail: '',
      reportDetailLength: 0,
      reportSubmitting: false,
      reportError: '',
    })
  },

  closeReport(): void {
    if (this.data.reportSubmitting) return
    this.setData({
      reportOpen: false,
      reportTargetId: '',
      reportTargetLabel: '',
      reportDetail: '',
      reportDetailLength: 0,
      reportError: '',
    })
  },

  stopPropagation(): void {
    // 阻止点击面板内部时冒泡到遮罩；无业务逻辑。
  },

  onReportReasonChange(event: WxPickerEvent): void {
    const index = Number(event.detail.value)
    if (!Number.isInteger(index) || index < 0 || index >= REPORT_REASONS.length) return
    this.setData({ reportReasonIndex: index })
  },

  onReportDetailInput(event: WxInputEvent): void {
    const detail = event.detail.value ?? ''
    this.setData({ reportDetail: detail, reportDetailLength: textLength(detail) })
  },

  async submitReport(): Promise<void> {
    if (this.data.reportSubmitting || !this.data.reportTargetId) return
    if (!this.data.loggedIn) {
      this.closeReport()
      this.login()
      return
    }
    const detail = this.data.reportDetail.trim()
    if (textLength(detail) > MAX_REPORT_DETAIL) {
      this.setData({ reportError: `补充说明最多 ${MAX_REPORT_DETAIL} 个字。` })
      return
    }

    const targetType = this.data.reportTargetType
    const targetId = this.data.reportTargetId
    const reason = REPORT_REASONS[this.data.reportReasonIndex].value as ReportReason
    this.setData({ reportSubmitting: true, reportError: '' })
    try {
      const result = await submitContentReport(targetType, targetId, reason, detail)
      this.setData({ reportSubmitting: false })
      this.closeReport()
      wx.showToast({
        title: result.duplicate ? '此前已提交过举报' : '举报已提交',
        icon: 'success',
      })
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) {
        this.setData({
          reportOpen: false,
          reportSubmitting: false,
          loggedIn: false,
          interactionHint: '登录已失效，请重新登录。',
        })
        return
      }
      this.setData({ reportSubmitting: false, reportError: toErrorMessage(reason) })
    }
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

  /** 从食堂窗口列表进入独立窗口详情；同页通过 windowId 分支复用点评与互动。 */
  openWindow(event: WxTapEvent) {
    const id = String(event.currentTarget.dataset.id ?? '')
    if (!/^[1-9]\d*$/.test(id)) return
    wx.navigateTo({
      url: `/pages/restaurant/detail/index?windowId=${encodeURIComponent(id)}`,
    })
  },

  /** 点评作者编号来自服务端只读视图；缺失时不跳转，避免打开空白主页。 */
  openAuthor(event: WxTapEvent) {
    const index = Number(event.currentTarget.dataset.index)
    const review = this.data.reviews[index]
    if (!review || !review.authorId) return
    wx.navigateTo({
      url: `/pages/user/profile/index?id=${encodeURIComponent(review.authorId)}`,
    })
  },

  onShareAppMessage() {
    const name = this.data.restaurant?.name ?? '泥电美食地图'
    return {
      title: `${name} · 泥电美食地图`,
      path: this.data.isWindow
        ? `/pages/restaurant/detail/index?windowId=${encodeURIComponent(this.data.restaurantId)}`
        : `/pages/restaurant/detail/index?id=${encodeURIComponent(this.data.restaurantId)}`,
    }
  },

  onShareTimeline() {
    return {
      title: this.data.restaurant?.name ?? '泥电美食地图',
      query: this.data.isWindow
        ? `windowId=${encodeURIComponent(this.data.restaurantId)}`
        : `id=${encodeURIComponent(this.data.restaurantId)}`,
    }
  },

  onUnload() {
    // 页面卸载后丢弃所有迟到响应，避免旧页面继续 setData。
    this.requestSequence++
  },
})
