import { login } from '../../modules/auth/api'
import { finishAuthNavigation } from '../../shared/auth/navigation'
import { saveSession } from '../../shared/auth/session'
import { toErrorMessage } from '../../shared/api/request'
import { isValidEmail } from '../../shared/lib/credentials'

interface LoginData {
  account: string
  password: string
  submitting: boolean
  errorMessage: string
  redirectPath: string
}

/**
 * 账号登录页。
 *
 * 服务端 login 字段同时接受用户名、注册邮箱或纯数字学号（自动补全学生邮箱后缀）；
 * 小程序不请求微信头像、昵称、手机号或 openid，密码只保存在页面内存。
 */
Page<LoginData>({
  data: {
    account: '',
    password: '',
    submitting: false,
    errorMessage: '',
    redirectPath: '',
  },

  onLoad(query) {
    const redirect = query.redirect ? decodeURIComponent(query.redirect) : ''
    const account = query.email ? decodeURIComponent(query.email) : ''
    this.setData({ redirectPath: redirect, account: account.slice(0, 254) })
  },

  onAccountInput(event: WxInputEvent) {
    this.setData({ account: event.detail.value, errorMessage: '' })
  },

  onPasswordInput(event: WxInputEvent) {
    this.setData({ password: event.detail.value, errorMessage: '' })
  },

  async submit() {
    if (this.data.submitting) return
    const account = this.data.account.trim()
    const password = this.data.password
    if (!account) {
      this.setData({ errorMessage: '请输入用户名、邮箱或学号。' })
      return
    }
    // 邮箱必须格式正确；用户名与学号交给服务端统一认证，不在客户端猜测账号是否存在。
    if (account.includes('@') && !isValidEmail(account)) {
      this.setData({ errorMessage: '邮箱格式不正确，请检查后重试。' })
      return
    }
    if (!password) {
      this.setData({ errorMessage: '请输入密码。' })
      return
    }

    this.setData({ submitting: true, errorMessage: '' })
    try {
      const session = await login(account, password)
      saveSession(session)
      wx.showToast({ title: '登录成功', icon: 'success', duration: 1200 })
      setTimeout(() => finishAuthNavigation(this.data.redirectPath), 300)
    } catch (reason) {
      this.setData({ errorMessage: toErrorMessage(reason) })
    } finally {
      this.setData({ submitting: false })
    }
  },

  /** 使用 redirectTo 进入注册，避免登录页留在返回栈中造成重复跳转。 */
  openRegister() {
    const redirect = this.data.redirectPath
    const query = redirect ? `?redirect=${encodeURIComponent(redirect)}` : ''
    wx.redirectTo({ url: `/pages/register/index${query}` })
  },

  /** 找回密码只支持邮箱；用户名或学号输入不预填。 */
  openPasswordReset() {
    const account = this.data.account.trim()
    const email = account.includes('@') ? account : ''
    const query = email ? `?email=${encodeURIComponent(email)}` : ''
    wx.redirectTo({ url: `/pages/password-reset/index${query}` })
  },
})
