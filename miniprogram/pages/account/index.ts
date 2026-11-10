import { logout } from '../../modules/auth/api'
import { fetchFollowSummary } from '../../modules/community/api'
import { clearSession, getCurrentUser, isLoggedIn } from '../../shared/auth/session'

type AccountData = {
  loggedIn: boolean
  username: string
  initial: string
  roleText: string
  userId: string
  /** 关注数据读取失败时显示占位符，不能用 0 冒充真实人数。 */
  followingText: string
  followersText: string
  socialLoading: boolean
  socialError: string
  loggingOut: boolean
}

/**
 * 个人中心展示账号状态、关注关系、我的点评与收藏入口以及退出登录。
 *
 * 新店投稿与资料纠错分别从找店页和店铺详情发起；
 * 投稿记录内可对待审申请修改/撤回、对被驳回申请重提。
 */
type AccountCustom = {
  socialSequence: number
  loadSocial(): Promise<void>
  performLogout(): Promise<void>
  openMyContent(event: WxTapEvent): void
  openContributions(): void
  openGrowth(): void
  openProfile(): void
  openPrivacy(): void
  openFollows(event: WxTapEvent): void
  openMyPublicProfile(): void
  confirmLogout(): void
}

Page<AccountData, AccountCustom>({
  data: {
    loggedIn: false,
    username: '',
    initial: 'G',
    roleText: '',
    userId: '',
    followingText: '—',
    followersText: '—',
    socialLoading: false,
    socialError: '',
    loggingOut: false,
  },

  socialSequence: 0,

  onShow() {
    const tabBar = this.getTabBar?.()
    if (tabBar) tabBar.setData({ selected: 3 })
    tabBar?.refreshUnread?.()
    const loggedIn = isLoggedIn()
    const user = getCurrentUser()
    this.setData({
      loggedIn,
      username: user?.username ?? '',
      initial: user ? Array.from(user.username)[0] ?? 'G' : 'G',
      roleText: user?.roles?.includes('ROLE_ADMIN') ? '管理员' : '校园用户',
      userId: user ? String(user.id) : '',
    })
    if (loggedIn) {
      void this.loadSocial()
    } else {
      // 会话可能在其他页面过期；未登录时不能保留上一次账号的人数。
      this.socialSequence++
      this.setData({
        followingText: '—',
        followersText: '—',
        socialLoading: false,
        socialError: '',
      })
    }
  },

  /** 关注人数每次回到本页都重新读取，在名单里取消关注后计数不会停留在旧值。 */
  async loadSocial(): Promise<void> {
    const user = getCurrentUser()
    if (!user) return
    const sequence = ++this.socialSequence
    this.setData({ socialLoading: true, socialError: '' })
    try {
      const social = await fetchFollowSummary(String(user.id))
      if (sequence !== this.socialSequence) return
      this.setData({
        followingText: String(social.followingCount),
        followersText: String(social.followersCount),
        socialLoading: false,
      })
    } catch {
      if (sequence !== this.socialSequence) return
      this.setData({
        followingText: '—',
        followersText: '—',
        socialLoading: false,
        socialError: '关注数据暂时无法读取',
      })
    }
  },

  login() {
    wx.navigateTo({
      url: `/pages/login/index?redirect=${encodeURIComponent('/pages/account/index')}`,
    })
  },

  /** 我的点评与收藏共用一个受保护页面，通过 tab 参数决定首屏分类。 */
  openMyContent(event: WxTapEvent) {
    const tab = event.currentTarget.dataset.tab
    if (tab !== 'reviews' && tab !== 'favorites') return
    wx.navigateTo({
      url: `/pages/account/content/index?tab=${tab}`,
    })
  },

  /** 投稿与纠错只读列表，默认展示全部状态。 */
  openContributions() {
    wx.navigateTo({
      url: '/pages/account/contributions/index',
    })
  },

  openGrowth() {
    wx.navigateTo({ url: '/pages/account/growth/index' })
  },

  openProfile() {
    wx.navigateTo({ url: '/pages/account/profile/index' })
  },

  openPrivacy() {
    wx.navigateTo({ url: '/pages/account/privacy/index' })
  },

  /** 关注与粉丝名单沿用同一页面，通过 kind 区分方向。 */
  openFollows(event: WxTapEvent) {
    const kind = event.currentTarget.dataset.kind
    if (kind !== 'FOLLOWING' && kind !== 'FOLLOWERS') return
    if (!this.data.userId) return
    wx.navigateTo({
      url:
        `/pages/user/follows/index?id=${encodeURIComponent(this.data.userId)}` +
        `&kind=${kind}`,
    })
  },

  openMyPublicProfile() {
    if (!this.data.userId) return
    wx.navigateTo({
      url: `/pages/user/profile/index?id=${encodeURIComponent(this.data.userId)}`,
    })
  },

  confirmLogout() {
    if (this.data.loggingOut) return
    wx.showModal({
      title: '退出登录',
      content: '退出后需要重新输入账号密码才能收藏或查看个人数据。',
      confirmText: '退出',
      success: (result) => {
        if (result.confirm) void this.performLogout()
      },
    })
  },

  async performLogout() {
    this.setData({ loggingOut: true })
    try {
      // 服务端撤销失败也要清理本地令牌；否则用户在网络异常时无法退出当前账号。
      await logout()
    } catch {
      // 令牌可能已经过期，服务端不可达不影响本地退出。
    } finally {
      clearSession()
      this.socialSequence++
      this.setData({
        loggedIn: false,
        username: '',
        initial: 'G',
        roleText: '',
        userId: '',
        followingText: '—',
        followersText: '—',
        socialLoading: false,
        socialError: '',
        loggingOut: false,
      })
      wx.showToast({ title: '已退出登录', icon: 'success' })
    }
  },
})
