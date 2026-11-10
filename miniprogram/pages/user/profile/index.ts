import {
  fetchFollowSummary,
  fetchPublicProfile,
  setFollow,
  type FollowSummary,
  type PublicProfile,
} from '../../../modules/community/api'
import { fetchUserFavorites } from '../../../modules/restaurant/api'
import type { FavoriteCard } from '../../../modules/restaurant/types'
import { fetchUserReviewGroups } from '../../../modules/review/api'
import type { MyReviewCard } from '../../../modules/review/types'
import { ApiError } from '../../../shared/api/request'
import { getCurrentUser, isLoggedIn } from '../../../shared/auth/session'
import { isLongId } from '../../../shared/lib/id'

type ProfileTab = 'reviews' | 'favorites'

type ProfileData = {
  userId: string
  loggedIn: boolean
  isSelf: boolean
  loading: boolean
  errorMessage: string
  profile: PublicProfile | null
  avatarText: string
  social: FollowSummary | null
  socialLoading: boolean
  socialError: string
  followBusy: boolean
  tab: ProfileTab
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
  /** 收藏被对方设为私密；与普通读取失败区分，避免反复重试同一个 403。 */
  favoritePrivate: boolean
  listLoading: boolean
  listLoadingMore: boolean
  listError: string
  listHint: string
}

type ProfileCustom = {
  /** 会话或主体变化时递增，使资料、关注人数与列表的迟到响应全部失效。 */
  contextSequence: number
  /** 列表分页或分类切换时递增，只让旧列表请求失效。 */
  listSequence: number
  skipNextShowReload: boolean
  loadProfile(): Promise<void>
  loadSocial(): Promise<void>
  loadList(tab: ProfileTab, page: number, reset: boolean): Promise<void>
  switchTab(event: WxTapEvent): void
  loadMore(): void
  toggleFollow(): void
  performFollow(active: boolean): Promise<void>
  openFollows(event: WxTapEvent): void
  openRestaurant(event: WxTapEvent): void
  retry(): void
  login(): void
  currentPath(): string
  handleAuthFailure(reason: unknown): boolean
}

/**
 * 其他用户的公开主页。
 *
 * 页面只展示白名单资料、关注人数、点评与收藏；主体不可用、名单私密和收藏私密
 * 都由服务端判定并原样提示，不用零值或空列表掩盖拒绝原因。
 */
Page<ProfileData, ProfileCustom>({
  data: {
    userId: '',
    loggedIn: false,
    isSelf: false,
    loading: false,
    errorMessage: '',
    profile: null,
    avatarText: '',
    social: null,
    socialLoading: false,
    socialError: '',
    followBusy: false,
    tab: 'reviews',
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
    favoritePrivate: false,
    listLoading: false,
    listLoadingMore: false,
    listError: '',
    listHint: '',
  },

  contextSequence: 0,
  listSequence: 0,
  skipNextShowReload: false,

  onLoad(query) {
    this.contextSequence++
    this.listSequence++
    const rawId = query.id ? decodeURIComponent(query.id) : ''
    const userId = isLongId(rawId) ? rawId : ''
    const loggedIn = isLoggedIn()
    const current = getCurrentUser()
    this.setData({
      userId,
      loggedIn,
      isSelf: !!userId && !!current && String(current.id) === userId,
    })
    if (!userId) {
      this.setData({ errorMessage: '用户编号无效，请返回后重试。' })
      return
    }
    if (!loggedIn) return
    this.skipNextShowReload = true
    void this.loadProfile()
    void this.loadSocial()
    void this.loadList('reviews', 1, true)
  },

  onShow() {
    const loggedIn = isLoggedIn()
    if (loggedIn !== this.data.loggedIn) {
      this.contextSequence++
      this.listSequence++
      this.setData({
        loggedIn,
        profile: null,
        social: null,
        reviews: [],
        favorites: [],
        reviewLoaded: false,
        favoriteLoaded: false,
        loading: false,
        listLoading: false,
        listLoadingMore: false,
        errorMessage: '',
        socialError: '',
        listError: '',
        listHint: '',
      })
      if (loggedIn && this.data.userId) {
        void this.loadProfile()
        void this.loadSocial()
        void this.loadList(this.data.tab, 1, true)
      }
      return
    }
    if (this.skipNextShowReload) {
      this.skipNextShowReload = false
      return
    }
    // 从关注名单返回时刷新人数；在名单里取消关注后不能继续显示旧计数。
    if (loggedIn && this.data.userId && this.data.profile) void this.loadSocial()
  },

  onUnload() {
    this.contextSequence++
    this.listSequence++
  },

  async loadProfile(): Promise<void> {
    const id = this.data.userId
    if (!id) return
    const sequence = this.contextSequence
    this.setData({ loading: true, errorMessage: '' })
    try {
      const profile = await fetchPublicProfile(id)
      if (sequence !== this.contextSequence) return
      this.setData({
        profile,
        avatarText: Array.from(profile.username)[0]?.toUpperCase() ?? '?',
        loading: false,
      })
    } catch (reason) {
      if (sequence !== this.contextSequence) return
      this.setData({ loading: false })
      if (!this.handleAuthFailure(reason)) {
        this.setData({ errorMessage: toMessage(reason) })
      }
    }
  },

  async loadSocial(): Promise<void> {
    const id = this.data.userId
    if (!id) return
    const sequence = this.contextSequence
    this.setData({ socialLoading: true, socialError: '' })
    try {
      const social = await fetchFollowSummary(id)
      if (sequence !== this.contextSequence) return
      this.setData({ social, socialLoading: false })
    } catch (reason) {
      if (sequence !== this.contextSequence) return
      this.setData({ socialLoading: false })
      if (!this.handleAuthFailure(reason)) {
        this.setData({ socialError: toMessage(reason) })
      }
    }
  },

  /** 未读取过的分类才拉取；私密拒绝会直接结束加载并保留明确提示。 */
  async loadList(tab: ProfileTab, page: number, reset: boolean): Promise<void> {
    const id = this.data.userId
    if (!id) return
    if (!isLoggedIn()) {
      this.setData({ loggedIn: false })
      return
    }
    const context = this.contextSequence
    const sequence = ++this.listSequence
    this.setData({
      listLoading: reset,
      listLoadingMore: !reset,
      listError: '',
      listHint: reset ? '' : this.data.listHint,
      favoritePrivate: reset ? false : this.data.favoritePrivate,
    })
    try {
      if (tab === 'reviews') {
        const result = await fetchUserReviewGroups(id, page)
        if (
          context !== this.contextSequence ||
          sequence !== this.listSequence ||
          this.data.tab !== tab
        ) {
          return
        }
        this.setData({
          reviews: reset ? result.records : this.data.reviews.concat(result.records),
          reviewPage: result.page,
          reviewTotalPages: result.totalPages,
          reviewTotalGroups: result.totalGroups,
          reviewTotalReviews: result.totalReviews,
          reviewLoaded: true,
          listLoading: false,
          listLoadingMore: false,
          listHint: '',
        })
        return
      }
      const result = await fetchUserFavorites(id, page)
      if (
        context !== this.contextSequence ||
        sequence !== this.listSequence ||
        this.data.tab !== tab
      ) {
        return
      }
      this.setData({
        favorites: reset ? result.records : this.data.favorites.concat(result.records),
        favoritePage: result.page,
        favoriteTotalPages: result.totalPages,
        favoriteTotal: result.total,
        favoriteLoaded: true,
        listLoading: false,
        listLoadingMore: false,
        listHint: '',
      })
    } catch (reason) {
      if (
        context !== this.contextSequence ||
        sequence !== this.listSequence ||
        this.data.tab !== tab
      ) {
        return
      }
      this.setData({ listLoading: false, listLoadingMore: false })
      if (this.handleAuthFailure(reason)) return
      // 收藏私密是正常的隐私状态，不提示“读取失败”，也不展示伪造的空列表。
      if (tab === 'favorites' && reason instanceof ApiError && reason.status === 403) {
        this.setData({
          favoriteLoaded: true,
          favoritePrivate: true,
          favoritePage: 1,
          favoriteTotalPages: 0,
          favoriteTotal: 0,
          listError: '该用户已将收藏设为私密，仅本人可见。',
        })
        return
      }
      if (reset) {
        this.setData({ listError: toMessage(reason) })
      } else {
        this.setData({ listHint: toMessage(reason) })
      }
    }
  },

  switchTab(event: WxTapEvent): void {
    const tab = event.currentTarget.dataset.tab
    if (tab !== 'reviews' && tab !== 'favorites') return
    if (tab === this.data.tab) return
    this.listSequence++
    this.setData({
      tab,
      listLoading: false,
      listLoadingMore: false,
      listError: '',
      listHint: '',
    })
    const loaded = tab === 'reviews' ? this.data.reviewLoaded : this.data.favoriteLoaded
    if (!loaded) void this.loadList(tab, 1, true)
  },

  loadMore(): void {
    if (this.data.listLoading || this.data.listLoadingMore || !this.data.loggedIn) return
    if (this.data.tab === 'reviews') {
      if (this.data.reviewPage >= this.data.reviewTotalPages) return
      void this.loadList('reviews', this.data.reviewPage + 1, false)
      return
    }
    if (this.data.favoritePage >= this.data.favoriteTotalPages) return
    void this.loadList('favorites', this.data.favoritePage + 1, false)
  },

  toggleFollow(): void {
    const social = this.data.social
    if (!social || this.data.isSelf || this.data.followBusy) return
    void this.performFollow(!social.following)
  },

  async performFollow(active: boolean): Promise<void> {
    const id = this.data.userId
    if (!id) return
    this.setData({ followBusy: true, socialError: '' })
    try {
      const social = await setFollow(id, active)
      if (id !== this.data.userId) return
      this.setData({
        social,
        followBusy: false,
      })
      wx.showToast({ title: active ? '已关注' : '已取消关注', icon: 'none' })
    } catch (reason) {
      if (id !== this.data.userId) return
      this.setData({ followBusy: false })
      if (this.handleAuthFailure(reason)) return
      // 结果未确认时重新读取服务端状态，避免按钮停留在乐观更新后的错误方向。
      this.setData({ socialError: toMessage(reason) })
      void this.loadSocial()
    }
  },

  openFollows(event: WxTapEvent): void {
    const kind = event.currentTarget.dataset.kind
    if (kind !== 'FOLLOWING' && kind !== 'FOLLOWERS') return
    wx.navigateTo({
      url:
        `/pages/user/follows/index?id=${encodeURIComponent(this.data.userId)}` +
        `&kind=${kind}`,
    })
  },

  openRestaurant(event: WxTapEvent): void {
    const id = event.currentTarget.dataset.id
    if (typeof id !== 'string' || !/^[1-9]\d*$/.test(id)) return
    if (this.data.tab === 'favorites') {
      const target = this.data.favorites.find((row) => row.restaurantId === id)
      if (!target || !target.available) return
      wx.navigateTo({ url: `/pages/restaurant/detail/index?id=${encodeURIComponent(id)}` })
      return
    }
    const review = this.data.reviews.find((row) => row.restaurantId === id)
    if (!review) return
    wx.navigateTo({
      url: review.isWindow
        ? `/pages/restaurant/detail/index?windowId=${encodeURIComponent(review.windowId)}`
        : `/pages/restaurant/detail/index?id=${encodeURIComponent(id)}`,
    })
  },

  retry(): void {
    if (!this.data.loggedIn || !this.data.userId) return
    void this.loadProfile()
    void this.loadSocial()
    void this.loadList(this.data.tab, 1, true)
  },

  login(): void {
    wx.navigateTo({
      url: `/pages/login/index?redirect=${encodeURIComponent(this.currentPath())}`,
    })
  },

  currentPath(): string {
    return `/pages/user/profile/index?id=${encodeURIComponent(this.data.userId)}`
  },

  handleAuthFailure(reason: unknown): boolean {
    if (!(reason instanceof ApiError) || reason.status !== 401) return false
    this.contextSequence++
    this.listSequence++
    this.setData({
      loggedIn: false,
      profile: null,
      social: null,
      reviews: [],
      favorites: [],
      reviewLoaded: false,
      favoriteLoaded: false,
      loading: false,
      listLoading: false,
      listLoadingMore: false,
      followBusy: false,
      errorMessage: '',
      socialError: '',
      listError: '',
      listHint: '',
    })
    wx.showToast({ title: '登录状态已失效', icon: 'none' })
    return true
  },
})

function toMessage(reason: unknown): string {
  if (reason instanceof Error && reason.message) return reason.message
  return '读取失败，请稍后重试。'
}
