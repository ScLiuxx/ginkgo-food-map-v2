import { resetPassword, sendEmailCode } from '../../modules/auth/api'
import { toErrorMessage } from '../../shared/api/request'
import { clearSession } from '../../shared/auth/session'
import { startCountdown } from '../../shared/lib/countdown'
import { PASSWORD_HINT, isValidEmail, isValidPassword } from '../../shared/lib/credentials'

interface ResetData {
  email: string
  verificationCode: string
  newPassword: string
  confirmPassword: string
  sendingCode: boolean
  submitting: boolean
  countdown: number
  errorMessage: string
}

interface ResetCustom {
  stopCountdown: (() => void) | null
  sendCode(): Promise<void>
  submit(): Promise<void>
}

/**
 * 找回密码页：邮箱验证码 + 新密码。
 *
 * 服务端更新密码后会让旧会话失效；这里也清理本地令牌，并返回登录页预填邮箱，
 * 不自动登录，避免旧设备继续停留在已登录状态。
 */
Page<ResetData, ResetCustom>({
  data: {
    email: '',
    verificationCode: '',
    newPassword: '',
    confirmPassword: '',
    sendingCode: false,
    submitting: false,
    countdown: 0,
    errorMessage: '',
  },

  stopCountdown: null,

  onLoad(query) {
    const email = query.email ? decodeURIComponent(query.email) : ''
    this.setData({ email: email.slice(0, 254) })
  },

  onUnload() {
    this.stopCountdown?.()
    this.stopCountdown = null
  },

  onEmailInput(event: WxInputEvent) {
    this.setData({ email: event.detail.value, errorMessage: '' })
  },

  onCodeInput(event: WxInputEvent) {
    this.setData({ verificationCode: event.detail.value, errorMessage: '' })
  },

  onPasswordInput(event: WxInputEvent) {
    this.setData({ newPassword: event.detail.value, errorMessage: '' })
  },

  onConfirmInput(event: WxInputEvent) {
    this.setData({ confirmPassword: event.detail.value, errorMessage: '' })
  },

  async sendCode(): Promise<void> {
    if (this.data.sendingCode || this.data.countdown > 0) return
    const email = this.data.email.trim()
    if (!isValidEmail(email)) {
      this.setData({ errorMessage: '请先填写有效的学校邮箱。' })
      return
    }
    this.setData({ sendingCode: true, errorMessage: '' })
    try {
      await sendEmailCode('password-reset', email)
      this.stopCountdown?.()
      this.stopCountdown = startCountdown(60, (remaining) => {
        this.setData({ countdown: remaining })
      })
      wx.showToast({ title: '验证码已发送', icon: 'none' })
    } catch (reason) {
      this.setData({ errorMessage: toErrorMessage(reason) })
    } finally {
      this.setData({ sendingCode: false })
    }
  },

  async submit(): Promise<void> {
    if (this.data.submitting) return
    const email = this.data.email.trim()
    const verificationCode = this.data.verificationCode.trim()
    const newPassword = this.data.newPassword

    if (!isValidEmail(email)) {
      this.setData({ errorMessage: '请填写有效的学校邮箱。' })
      return
    }
    if (!/^\d{6}$/.test(verificationCode)) {
      this.setData({ errorMessage: '请输入 6 位数字验证码。' })
      return
    }
    if (!isValidPassword(newPassword)) {
      this.setData({ errorMessage: `新密码需为${PASSWORD_HINT}。` })
      return
    }
    if (newPassword !== this.data.confirmPassword) {
      this.setData({ errorMessage: '两次输入的密码不一致。' })
      return
    }

    this.setData({ submitting: true, errorMessage: '' })
    try {
      await resetPassword(email, verificationCode, newPassword)
      clearSession()
      this.stopCountdown?.()
      this.stopCountdown = null
      wx.showToast({ title: '密码已重置', icon: 'success', duration: 1000 })
      setTimeout(() => {
        wx.redirectTo({ url: `/pages/login/index?email=${encodeURIComponent(email)}` })
      }, 300)
    } catch (reason) {
      // 验证码错误或账号不可用由服务端统一返回，不在这里推测具体原因。
      this.setData({ errorMessage: toErrorMessage(reason) })
    } finally {
      this.setData({ submitting: false })
    }
  },
})
