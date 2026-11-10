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
 * 个人中心第一批只展示账号状态与退出登录。
 *
 * 我的点评、收藏列表、投稿和资料设置在后续步骤接入；
 * 这里不放置点击无效的菜单，避免让用户误以为功能已经可用。
 */
type AccountCustom = {
  performLogout(): Promise<void>
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
