import { isLoggedIn } from '../../shared/auth/session'

type MessagesData = {
  loggedIn: boolean
}

/**
 * 消息中心占位页。
 *
 * 消息读取依赖登录态与分类分页接口；第一批先明确登录入口，
 * 不在未登录时发起必然失败的请求，也不展示虚构未读数。
 */
Page<MessagesData, {}>({
  data: {
    loggedIn: false,
  },

  onShow() {
    this.setData({ loggedIn: isLoggedIn() })
  },

  login() {
    wx.navigateTo({
      url: `/pages/login/index?redirect=${encodeURIComponent('/pages/messages/index')}`,
    })
  },
})
