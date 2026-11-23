import { login } from '../../modules/auth/api'
import { finishAuthNavigation } from '../../shared/auth/navigation'
import { saveSession } from '../../shared/auth/session'
import { toErrorMessage } from '../../shared/api/request'
import {
  CAMPUS_EMAIL_SUFFIX,
  MAX_STUDENT_NUMBER_LENGTH,
  isValidEmail,
} from '../../shared/lib/credentials'

interface LoginData {
  mode: 'account' | 'student'
  account: string
  studentNumber: string
  campusEmailSuffix: string
  studentMaxLength: number
  password: string
  submitting: boolean
  errorMessage: string
  redirectPath: string
}

/**
 * 账号登录页。
 *
 * 学号只有在学号模式补全邮箱后缀，普通账号模式保留纯数字用户名。
 * 小程序不请求微信头像、昵称、手机号或 openid，密码只保存在页面内存。
 */
Page<LoginData>({
  data: {
    mode: 'account',
    account: '',
    studentNumber: '',
    campusEmailSuffix: CAMPUS_EMAIL_SUFFIX,
    studentMaxLength: MAX_STUDENT_NUMBER_LENGTH,
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
    if (this.data.mode === 'student') {
      this.setData({ studentNumber: event.detail.value, errorMessage: '' })
    } else {
      this.setData({ account: event.detail.value, errorMessage: '' })
    }
  },

  changeMode(event: WxTapEvent) {
    const mode = event.currentTarget.dataset.mode
    if (this.data.submitting || (mode !== 'account' && mode !== 'student') || mode === this.data.mode) {
      return
    }
    this.setData({ mode, password: '', errorMessage: '' })
  },

  onPasswordInput(event: WxInputEvent) {
    this.setData({ password: event.detail.value, errorMessage: '' })
  },

  async submit() {
    if (this.data.submitting) return
    const studentMode = this.data.mode === 'student'
    const account = (studentMode ? this.data.studentNumber : this.data.account).trim()
    const password = this.data.password
    if (!account) {
      this.setData({ errorMessage: studentMode ? '请输入学号。' : '请输入用户名或邮箱。' })
      return
    }
    if (studentMode && (!/^[0-9]+$/.test(account) || account.length > MAX_STUDENT_NUMBER_LENGTH)) {
      this.setData({ errorMessage: '请输入数字学号。' })
      return
    }
    if (!studentMode && account.includes('@') && !isValidEmail(account)) {
      this.setData({ errorMessage: '邮箱格式不正确，请检查后重试。' })
      return
    }
    if (!password) {
      this.setData({ errorMessage: '请输入密码。' })
      return
    }

    this.setData({ submitting: true, errorMessage: '' })
    try {
      const session = await login(studentMode ? account + CAMPUS_EMAIL_SUFFIX : account, password)
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

  /** 找回密码使用校园邮箱，学号模式下可沿用已填写的学号。 */
  openPasswordReset() {
    const account = this.data.account.trim()
    const studentNumber = this.data.studentNumber.trim()
    const email = this.data.mode === 'student'
      ? (/^[0-9]+$/.test(studentNumber) ? studentNumber + CAMPUS_EMAIL_SUFFIX : '')
      : (account.includes('@') ? account : '')
    const query = email ? `?email=${encodeURIComponent(email)}` : ''
    wx.redirectTo({ url: `/pages/password-reset/index${query}` })
  },
})
