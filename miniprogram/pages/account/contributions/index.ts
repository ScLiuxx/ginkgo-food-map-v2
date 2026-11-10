import {
  fetchMyContributions,
  type ContributionCard,
  type ContributionFilter,
  type ContributionListView,
} from '../../../modules/restaurant/contribution'
import { ApiError } from '../../../shared/api/request'
import { isLoggedIn } from '../../../shared/auth/session'

type ContributionTab = { key: ContributionFilter; label: string; count: number }

type ContributionListData = {
  loggedIn: boolean
  loading: boolean
  loadingMore: boolean
  errorMessage: string
  hint: string
  filter: ContributionFilter
  filters: ContributionTab[]
  counts: Record<ContributionFilter, number>
  items: ContributionCard[]
  loaded: boolean
  page: number
  totalPages: number
  total: number
}

type ContributionListCustom = {
  requestSequence: number
  load(filter: ContributionFilter, page: number, reset: boolean): Promise<void>
  reload(): Promise<void>
  switchFilter(event: WxTapEvent): void
  loadMore(): void
  openDetail(event: WxTapEvent): void
  login(): void
  currentPath(): string
  handleAuthFailure(reason: unknown): boolean
}

const FILTERS: Array<{ key: ContributionFilter; label: string }> = [
  { key: 'ALL', label: '全部' },
  { key: 'PENDING', label: '待审核' },
  { key: 'APPROVED', label: '已通过' },
  { key: 'REJECTED', label: '已驳回' },
  { key: 'WITHDRAWN', label: '已撤回' },
]
const EMPTY_COUNTS: Record<ContributionFilter, number> = {
  ALL: 0,
  PENDING: 0,
  APPROVED: 0,
  REJECTED: 0,
  WITHDRAWN: 0,
}

/** 把服务端状态计数投影到固定筛选栏，顺序不随响应变化。 */
function toFilters(counts: Record<ContributionFilter, number>): ContributionTab[] {
  return FILTERS.map((item) => ({ ...item, count: counts[item.key] }))
}

Page<ContributionListData, ContributionListCustom>({
  data: {
    loggedIn: false,
    loading: false,
    loadingMore: false,
    errorMessage: '',
    hint: '',
    filter: 'ALL',
    filters: toFilters(EMPTY_COUNTS),
    counts: EMPTY_COUNTS,
    items: [],
    loaded: false,
    page: 0,
    totalPages: 0,
    total: 0,
  },

  requestSequence: 0,

  onLoad(query) {
    const filter = isFilter(query.filter) ? query.filter : 'ALL'
    const loggedIn = isLoggedIn()
    this.setData({ filter, loggedIn })
    if (loggedIn) void this.load(filter, 1, true)
  },

  onShow() {
    const loggedIn = isLoggedIn()
    if (loggedIn !== this.data.loggedIn) {
      // 换号或登录失效后不能继续展示上一账号的投稿记录。
      this.requestSequence += 1
      this.setData({
        loggedIn,
        items: [],
        loaded: false,
        loading: false,
        loadingMore: false,
        errorMessage: '',
        hint: '',
        counts: EMPTY_COUNTS,
        filters: toFilters(EMPTY_COUNTS),
      })
    }
    if (!loggedIn) return
    if (!this.data.loaded && !this.data.loading) {
      void this.load(this.data.filter, 1, true)
    }
  },

  onPullDownRefresh() {
    if (!isLoggedIn()) {
      wx.stopPullDownRefresh()
      return
    }
    void this.load(this.data.filter, 1, true).finally(() => wx.stopPullDownRefresh())
  },

  currentPath(): string {
    return `/pages/account/contributions/index?filter=${this.data.filter}`
  },

  async load(filter: ContributionFilter, page: number, reset: boolean): Promise<void> {
    if (!isLoggedIn()) {
      this.setData({ loggedIn: false })
      return
    }
    const sequence = ++this.requestSequence
    this.setData({
      loading: reset,
      loadingMore: !reset,
      errorMessage: '',
      hint: reset ? '' : this.data.hint,
    })
    try {
      const result: ContributionListView = await fetchMyContributions(filter, page)
      if (sequence !== this.requestSequence || this.data.filter !== filter) return
      this.setData({
        items: reset ? result.records : this.data.items.concat(result.records),
        counts: result.counts,
        filters: toFilters(result.counts),
        page: result.page,
        totalPages: result.totalPages,
        total: result.total,
        loaded: true,
        loading: false,
        loadingMore: false,
        hint: '',
      })
    } catch (reason) {
      if (sequence !== this.requestSequence || this.data.filter !== filter) return
      this.setData({ loading: false, loadingMore: false })
      if (this.handleAuthFailure(reason)) return
      if (reset) {
        this.setData({ errorMessage: toMessage(reason) })
      } else {
        this.setData({ hint: toMessage(reason) })
      }
    }
  },

  async reload(): Promise<void> {
    await this.load(this.data.filter, 1, true)
  },

  switchFilter(event: WxTapEvent): void {
    const filter = event.currentTarget.dataset.filter
    if (!isFilter(filter) || filter === this.data.filter) return
    // 切换筛选时让旧请求失效，避免串状态写入新列表。
    this.requestSequence += 1
    this.setData({
      filter,
      items: [],
      loaded: false,
      loading: false,
      loadingMore: false,
      errorMessage: '',
      hint: '',
    })
    void this.load(filter, 1, true)
  },

  loadMore(): void {
    if (this.data.loading || this.data.loadingMore || !this.data.loggedIn) return
    if (this.data.page >= this.data.totalPages) return
    void this.load(this.data.filter, this.data.page + 1, false)
  },

  openDetail(event: WxTapEvent): void {
    const id = event.currentTarget.dataset.id
    if (typeof id !== 'string' || !/^[1-9]\d*$/.test(id)) return
    wx.navigateTo({
      url: `/pages/account/contributions/detail/index?id=${encodeURIComponent(id)}`,
    })
  },

  login(): void {
    wx.navigateTo({
      url: `/pages/login/index?redirect=${encodeURIComponent(this.currentPath())}`,
    })
  },

  handleAuthFailure(reason: unknown): boolean {
    if (!(reason instanceof ApiError) || reason.status !== 401) return false
    this.requestSequence += 1
    this.setData({
      loggedIn: false,
      items: [],
      loaded: false,
      loading: false,
      loadingMore: false,
      errorMessage: '',
      hint: '',
      counts: EMPTY_COUNTS,
      filters: toFilters(EMPTY_COUNTS),
    })
    wx.showToast({ title: '登录状态已失效', icon: 'none' })
    return true
  },
})

function isFilter(value: unknown): value is ContributionFilter {
  return value === 'ALL' || value === 'PENDING' || value === 'APPROVED'
    || value === 'REJECTED' || value === 'WITHDRAWN'
}

function toMessage(reason: unknown): string {
  if (reason instanceof Error && reason.message) return reason.message
  return '投稿记录读取失败，请稍后重试。'
}
