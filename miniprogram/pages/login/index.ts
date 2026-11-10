import { login } from '../../modules/auth/api'
import { finishAuthNavigation } from '../../shared/auth/navigation'
import { saveSession } from '../../shared/auth/session'
import { toErrorMessage } from '../../shared/api/request'
import { isValidEmail } from '../../shared/lib/credentials'

interface LoginData {
  email: string
  password: string
  submitting: boolean
  errorMessage: string
  redirectPath: string
}

/**
 * 邮箱登录页。
 *
 * 小程序只使用账号体系中的注册邮箱登录，不请求微信头像、昵称、手机号或 openid；
 * 密码只保存在页面内存，提交后立即丢弃，不写入本地存储。
 */
Page<LoginData>({
  data: {
    email: '',
    password: '',
    submitting: false,
    errorMessage: '',
    redirectPath: '',
  },

  onLoad(query) {
    const redirect = query.redirect ? decodeURIComponent(query.redirect) : ''
    const email = query.email ? decodeURIComponent(query.email) : ''
    this.setData({ redirectPath: redirect, email: email.slice(0, 254) })
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
    if (!isValidEmail(email)) {
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

  /** 找回密码时带上当前输入的邮箱，重置成功后登录页可预填。 */
  openPasswordReset() {
    const email = this.data.email.trim()
    const query = email ? `?email=${encodeURIComponent(email)}` : ''
    wx.redirectTo({ url: `/pages/password-reset/index${query}` })
  },
})
