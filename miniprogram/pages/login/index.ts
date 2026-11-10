import { login } from '../../modules/auth/api'
import { saveSession } from '../../shared/auth/session'
import { toErrorMessage } from '../../shared/api/request'

interface LoginData {
  username: string
  password: string
  submitting: boolean
  errorMessage: string
  redirectPath: string
}

interface LoginCustom {
  finishLogin(): void
}

/** 登录页只负责收集账号密码与处理跳转，密码不进入本地存储。 */
Page<LoginData, LoginCustom>({
  data: {
    username: '',
    password: '',
    submitting: false,
    errorMessage: '',
    redirectPath: '',
  },

  onLoad(query) {
    const redirect = query.redirect ? decodeURIComponent(query.redirect) : ''
    this.setData({ redirectPath: redirect })
  },

  onUsernameInput(event: WxInputEvent) {
    this.setData({ username: event.detail.value, errorMessage: '' })
  },

  onPasswordInput(event: WxInputEvent) {
    this.setData({ password: event.detail.value, errorMessage: '' })
  },

  async submit() {
    if (this.data.submitting) return
    const username = this.data.username.trim()
    const password = this.data.password
    if (!username) {
      this.setData({ errorMessage: '请输入用户名或注册邮箱。' })
      return
    }
    if (!password) {
      this.setData({ errorMessage: '请输入密码。' })
      return
    }

    this.setData({ submitting: true, errorMessage: '' })
    try {
      const session = await login(username, password)
      saveSession(session)
      wx.showToast({ title: '登录成功', icon: 'success', duration: 1200 })
      setTimeout(() => this.finishLogin(), 300)
    } catch (reason) {
      this.setData({ errorMessage: toErrorMessage(reason) })
    } finally {
      this.setData({ submitting: false })
    }
  },

  /** 优先回到调用页；没有来源页时回到找店首页，避免登录后停在空白栈。 */
  finishLogin() {
    const redirect = this.data.redirectPath
    if (redirect.startsWith('/pages/') && !redirect.startsWith('/pages/login/')) {
      const tabPages = [
        '/pages/home/index',
        '/pages/map/index',
        '/pages/messages/index',
        '/pages/account/index',
      ]
      if (tabPages.includes(redirect)) {
        wx.switchTab({ url: redirect })
      } else {
        wx.redirectTo({ url: redirect })
      }
      return
    }
    if (getCurrentPages().length > 1) {
      wx.navigateBack()
      return
    }
    wx.switchTab({ url: '/pages/home/index' })
  },
})
