import { logout } from '../../modules/auth/api'
import { clearSession, getCurrentUser, isLoggedIn } from '../../shared/auth/session'

type AccountData = {
  loggedIn: boolean
  username: string
  initial: string
  roleText: string
  loggingOut: boolean
}

/**
 * 个人中心展示账号状态、我的点评与收藏入口以及退出登录。
 *
 * 新店投稿与资料纠错分别从找店页和店铺详情发起；
 * 投稿与纠错记录仍只支持查看，不放置点击无效的编辑、撤回或重提入口。
 */
type AccountCustom = {
  performLogout(): Promise<void>
  openMyContent(event: WxTapEvent): void
  openContributions(): void
  openGrowth(): void
  openProfile(): void
  openPrivacy(): void
}

Page<AccountData, AccountCustom>({
  data: {
    loggedIn: false,
    username: '',
    initial: 'G',
    roleText: '',
    loggingOut: false,
  },

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
    })
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
      this.setData({
        loggedIn: false,
        username: '',
        initial: 'G',
        roleText: '',
        loggingOut: false,
      })
      wx.showToast({ title: '已退出登录', icon: 'success' })
    }
  },
})
