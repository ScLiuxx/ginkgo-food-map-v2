import { login } from '../../modules/auth/api'
import { saveSession } from '../../shared/auth/session'
import { toErrorMessage } from '../../shared/api/request'

interface LoginData {
  email: string
  password: string
  submitting: boolean
  errorMessage: string
  redirectPath: string
}

interface LoginCustom {
  finishLogin(): void
}

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

/**
 * 邮箱登录页。
 *
 * 小程序只使用账号体系中的注册邮箱登录，不请求微信头像、昵称、手机号或 openid；
 * 密码只保存在页面内存，提交后立即丢弃，不写入本地存储。
 */
Page<LoginData, LoginCustom>({
  data: {
    email: '',
    password: '',
    submitting: false,
    errorMessage: '',
    redirectPath: '',
  },

  onLoad(query) {
    const redirect = query.redirect ? decodeURIComponent(query.redirect) : ''
    this.setData({ redirectPath: redirect })
  },

  onEmailInput(event: WxInputEvent) {
    this.setData({ email: event.detail.value, errorMessage: '' })
  },

  onPasswordInput(event: WxInputEvent) {
    this.setData({ password: event.detail.value, errorMessage: '' })
  },

  async submit() {
    if (this.data.submitting) return
    const email = this.data.email.trim()
    const password = this.data.password
    if (!email) {
      this.setData({ errorMessage: '请输入注册邮箱。' })
      return
    }
    if (email.length > 254 || !EMAIL_PATTERN.test(email)) {
      this.setData({ errorMessage: '邮箱格式不正确，请检查后重试。' })
      return
    }
    if (!password) {
      this.setData({ errorMessage: '请输入密码。' })
      return
    }

    this.setData({ submitting: true, errorMessage: '' })
    try {
      const session = await login(email, password)
      saveSession(session)
      wx.showToast({ title: '登录成功', icon: 'success', duration: 1200 })
      setTimeout(() => this.finishLogin(), 300)
    } catch (reason) {
      this.setData({ errorMessage: toErrorMessage(reason) })
    } finally {
      this.setData({ submitting: false })
    }
  },

  /** 优先返回调用页；若调用页仍在栈中则直接返回，避免详情页重复入栈。 */
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
        return
      }
      const pages = getCurrentPages()
      const previous = pages[pages.length - 2]
      if (previous && `/${previous.route}` === redirect.split('?')[0]) {
        wx.navigateBack()
        return
      }
      wx.redirectTo({ url: redirect })
      return
    }
    if (getCurrentPages().length > 1) {
      wx.navigateBack()
      return
    }
    wx.switchTab({ url: '/pages/home/index' })
  },
})
