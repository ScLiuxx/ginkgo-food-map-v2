import { registerAndLogin, sendEmailCode } from '../../modules/auth/api'
import { finishAuthNavigation } from '../../shared/auth/navigation'
import { saveSession } from '../../shared/auth/session'
import { toErrorMessage } from '../../shared/api/request'
import { startCountdown } from '../../shared/lib/countdown'
import {
  PASSWORD_HINT,
  USERNAME_HINT,
  isValidEmail,
  isValidPassword,
  isValidUsername,
} from '../../shared/lib/credentials'

interface RegisterData {
  email: string
  verificationCode: string
  username: string
  password: string
  confirmPassword: string
  sendingCode: boolean
  submitting: boolean
  countdown: number
  errorMessage: string
  redirectPath: string
}

interface RegisterCustom {
  stopCountdown: (() => void) | null
  sendCode(): Promise<void>
  submit(): Promise<void>
}

/**
 * 注册页：学校邮箱验证码 + 用户名 + 密码，成功后直接建立会话。
 *
 * 验证码仅用于本次提交，不写入本地存储；密码确认只在前端比较，不作为接口字段。
 */
Page<RegisterData, RegisterCustom>({
  data: {
    email: '',
    verificationCode: '',
    username: '',
    password: '',
    confirmPassword: '',
    sendingCode: false,
    submitting: false,
    countdown: 0,
    errorMessage: '',
    redirectPath: '',
  },

  stopCountdown: null,

  onLoad(query) {
    const redirect = query.redirect ? decodeURIComponent(query.redirect) : ''
    this.setData({ redirectPath: redirect })
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

  onUsernameInput(event: WxInputEvent) {
    this.setData({ username: event.detail.value, errorMessage: '' })
  },

  onPasswordInput(event: WxInputEvent) {
    this.setData({ password: event.detail.value, errorMessage: '' })
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
      await sendEmailCode('register', email)
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
    const username = this.data.username.trim()
    const password = this.data.password

    if (!isValidEmail(email)) {
      this.setData({ errorMessage: '请填写有效的学校邮箱。' })
      return
    }
    if (!/^\d{6}$/.test(verificationCode)) {
      this.setData({ errorMessage: '请输入 6 位数字验证码。' })
      return
    }
    if (!isValidUsername(username)) {
      this.setData({ errorMessage: `用户名需为${USERNAME_HINT}。` })
      return
    }
    if (!isValidPassword(password)) {
      this.setData({ errorMessage: `密码需为${PASSWORD_HINT}。` })
      return
    }
    if (password !== this.data.confirmPassword) {
      this.setData({ errorMessage: '两次输入的密码不一致。' })
      return
    }

    this.setData({ submitting: true, errorMessage: '' })
    try {
      const session = await registerAndLogin({
        email,
        verificationCode,
        username,
        password,
      })
      saveSession(session)
      this.stopCountdown?.()
      this.stopCountdown = null
      wx.showToast({ title: '注册成功', icon: 'success', duration: 1000 })
      setTimeout(() => finishAuthNavigation(this.data.redirectPath), 300)
    } catch (reason) {
      // 验证码已被消费或用户名冲突等失败原因由服务端返回，保留表单内容便于修改。
      this.setData({ errorMessage: toErrorMessage(reason) })
    } finally {
      this.setData({ submitting: false })
    }
  },
})
