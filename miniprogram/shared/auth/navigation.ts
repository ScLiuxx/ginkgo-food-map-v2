/**
 * 登录或注册成功后的统一跳转。
 *
 * 优先回到发起登录的受保护页面：Tab 页用 switchTab，上一页就是目标页时直接返回，
 * 其余页面用 redirectTo 避免登录页留在返回栈中。
 */
export function finishAuthNavigation(redirectPath: string): void {
  if (redirectPath.startsWith('/pages/') && !redirectPath.startsWith('/pages/login/')) {
    const tabPages = [
      '/pages/home/index',
      '/pages/map/index',
      '/pages/messages/index',
      '/pages/account/index',
    ]
    if (tabPages.includes(redirectPath)) {
      wx.switchTab({ url: redirectPath })
      return
    }
    const pages = getCurrentPages()
    const previous = pages[pages.length - 2]
    if (previous && `/${previous.route}` === redirectPath.split('?')[0]) {
      wx.navigateBack()
      return
    }
    wx.redirectTo({ url: redirectPath })
    return
  }
  if (getCurrentPages().length > 1) {
    wx.navigateBack()
    return
  }
  wx.switchTab({ url: '/pages/home/index' })
}
