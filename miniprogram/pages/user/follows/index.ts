import {
  fetchFollowPage,
  setFollow,
  type FollowKind,
  type FollowMember,
} from '../../../modules/community/api'
import { ApiError } from '../../../shared/api/request'
import { getCurrentUser, isLoggedIn } from '../../../shared/auth/session'
import { isLongId } from '../../../shared/lib/id'

type FollowsData = {
  userId: string
  kind: FollowKind
  kindLabel: string
  loggedIn: boolean
  isSelf: boolean
  records: FollowMember[]
  total: number
  page: number
  totalPages: number
  loading: boolean
  errorMessage: string
  hint: string
  busyId: string
}

type FollowsCustom = {
  contextSequence: number
  skipNextShowReload: boolean
  load(page: number): Promise<void>
  reload(): void
  goPage(event: WxTapEvent): void
  openMember(event: WxTapEvent): void
  cancelFollow(event: WxTapEvent): void
  performCancel(member: FollowMember): Promise<void>
  login(): void
  currentPath(): string
  handleAuthFailure(reason: unknown): boolean
}

/**
 * 关注或粉丝名单。
 *
 * 名单是否可见由服务端判定；页面只绑定用户与方向，不能通过参数读取他人私密名单。
 * 仅在查看自己的关注名单时提供取消关注，取消后按服务端最新总数重新分页。
 */
Page<FollowsData, FollowsCustom>({
  data: {
    userId: '',
    kind: 'FOLLOWING',
    kindLabel: '关注名单',
    loggedIn: false,
    isSelf: false,
    records: [],
    total: 0,
    page: 0,
    totalPages: 0,
    loading: false,
    errorMessage: '',
    hint: '',
    busyId: '',
  },

  contextSequence: 0,
  skipNextShowReload: false,

  onLoad(query) {
    this.contextSequence++
    const userId = query.id ? decodeURIComponent(query.id) : ''
    const kind: FollowKind = query.kind === 'FOLLOWERS' ? 'FOLLOWERS' : 'FOLLOWING'
    const loggedIn = isLoggedIn()
    const current = getCurrentUser()
    const valid = isLongId(userId)
    this.setData({
      userId: valid ? userId : '',
      kind,
      kindLabel: kind === 'FOLLOWING' ? '关注名单' : '粉丝名单',
      loggedIn,
      isSelf: valid && !!current && String(current.id) === userId,
      errorMessage: valid ? '' : '用户编号无效，请返回后重试。',
    })
    wx.setNavigationBarTitle({ title: kind === 'FOLLOWING' ? '关注名单' : '粉丝名单' })
    if (!valid || !loggedIn) return
    this.skipNextShowReload = true
    void this.load(1)
  },

  onShow() {
    const loggedIn = isLoggedIn()
    if (loggedIn !== this.data.loggedIn) {
      this.contextSequence++
      this.setData({
        loggedIn,
        records: [],
        total: 0,
        page: 0,
        totalPages: 0,
        loading: false,
        errorMessage: '',
        hint: '',
        busyId: '',
      })
      if (loggedIn && this.data.userId) void this.load(1)
      return
    }
    if (this.skipNextShowReload) {
      this.skipNextShowReload = false
      return
    }
    // 从成员主页返回时可能已改变关注状态，刷新当前页而不是保留旧按钮方向。
    if (loggedIn && this.data.page > 0) void this.load(this.data.page)
  },

  onUnload() {
    this.contextSequence++
  },

  async load(page: number): Promise<void> {
    const userId = this.data.userId
    if (!userId) return
    if (!isLoggedIn()) {
      this.setData({ loggedIn: false })
      return
    }
    const sequence = ++this.contextSequence
    this.setData({ loading: true, errorMessage: '', hint: '', busyId: '' })
    try {
      const result = await fetchFollowPage(userId, this.data.kind, page)
      if (sequence !== this.contextSequence) return
      this.setData({
        records: result.records,
        total: result.total,
        page: result.page,
        totalPages: result.totalPages,
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

  reload(): void {
    void this.load(this.data.page > 0 ? this.data.page : 1)
  },

  /** 分页只允许在服务端给出的页数内移动，越界或加载中点击直接忽略。 */
  goPage(event: WxTapEvent): void {
    if (this.data.loading || this.data.busyId) return
    const page = Number(event.currentTarget.dataset.page)
    if (!Number.isInteger(page) || page < 1 || page > this.data.totalPages) return
    if (page === this.data.page) return
    void this.load(page)
  },

  openMember(event: WxTapEvent): void {
    const id = event.currentTarget.dataset.id
    if (typeof id !== 'string' || !isLongId(id)) return
    wx.navigateTo({ url: `/pages/user/profile/index?id=${encodeURIComponent(id)}` })
  },

  cancelFollow(event: WxTapEvent): void {
    if (
      !this.data.isSelf ||
      this.data.kind !== 'FOLLOWING' ||
      this.data.busyId ||
      this.data.loading
    ) {
      return
    }
    const id = event.currentTarget.dataset.id
    if (typeof id !== 'string') return
    const member = this.data.records.find((row) => row.userId === id)
    if (!member) return
    wx.showModal({
      title: '取消关注',
      content: `确定不再关注“${member.username}”？`,
      confirmText: '取消关注',
      confirmColor: '#b83a34',
      success: (result) => {
        if (result.confirm) void this.performCancel(member)
      },
    })
  },

  async performCancel(member: FollowMember): Promise<void> {
    if (this.data.busyId) return
    this.setData({ busyId: member.userId, hint: '' })
    try {
      await setFollow(member.userId, false)
      if (!this.data.userId) return
      // 当前页可能因取消关注而变空，回到第一页按最新总数重新读取。
      const remaining = this.data.records.filter((row) => row.userId !== member.userId).length
      const target = remaining === 0 && this.data.page > 1 ? 1 : this.data.page
      wx.showToast({ title: '已取消关注', icon: 'none' })
      await this.load(target)
    } catch (reason) {
      if (this.handleAuthFailure(reason)) return
      this.setData({ hint: `取消结果未确认：${toMessage(reason)}，请刷新核对。` })
      await this.load(this.data.page > 0 ? this.data.page : 1)
    } finally {
      this.setData({ busyId: '' })
    }
  },

  login(): void {
    wx.navigateTo({
      url: `/pages/login/index?redirect=${encodeURIComponent(this.currentPath())}`,
    })
  },

  currentPath(): string {
    return (
      `/pages/user/follows/index?id=${encodeURIComponent(this.data.userId)}` +
      `&kind=${this.data.kind}`
    )
  },

  handleAuthFailure(reason: unknown): boolean {
    if (!(reason instanceof ApiError) || reason.status !== 401) return false
    this.contextSequence++
    this.setData({
      loggedIn: false,
      records: [],
      total: 0,
      page: 0,
      totalPages: 0,
      loading: false,
      errorMessage: '',
      hint: '',
      busyId: '',
    })
    wx.showToast({ title: '登录状态已失效', icon: 'none' })
    return true
  },
})

function toMessage(reason: unknown): string {
  if (reason instanceof Error && reason.message) return reason.message
  return '读取失败，请稍后重试。'
}
