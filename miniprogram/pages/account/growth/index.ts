import { fetchGrowth, type GrowthView } from '../../../modules/account/api'
import { ApiError } from '../../../shared/api/request'
import { isLoggedIn } from '../../../shared/auth/session'

type GrowthData = {
  loggedIn: boolean
  loading: boolean
  errorMessage: string
  growth: GrowthView | null
}

type GrowthCustom = {
  active: boolean
  requestSequence: number
  load(): Promise<void>
  login(): void
  currentPath(): string
  handleAuthFailure(reason: unknown): boolean
}

Page<GrowthData, GrowthCustom>({
  data: {
    loggedIn: false,
    loading: false,
    errorMessage: '',
    growth: null,
  },

  active: true,
  requestSequence: 0,

  onLoad() {
    const loggedIn = isLoggedIn()
    this.setData({ loggedIn })
    if (loggedIn) void this.load()
  },

  onShow() {
    const loggedIn = isLoggedIn()
    if (loggedIn === this.data.loggedIn) return
    this.requestSequence += 1
    this.setData({ loggedIn, growth: null, errorMessage: '', loading: false })
    if (loggedIn) void this.load()
  },

  onUnload() {
    this.active = false
  },

  currentPath(): string {
    return '/pages/account/growth/index'
  },

  async load(): Promise<void> {
    if (!isLoggedIn()) {
      this.setData({ loggedIn: false })
      return
    }
    const sequence = ++this.requestSequence
    this.setData({ loading: true, errorMessage: '' })
    try {
      const growth = await fetchGrowth()
      if (!this.active || sequence !== this.requestSequence) return
      this.setData({ growth, loading: false })
    } catch (reason) {
      if (!this.active || sequence !== this.requestSequence) return
      this.setData({ loading: false })
      if (this.handleAuthFailure(reason)) return
      this.setData({
        errorMessage:
          reason instanceof Error && reason.message
            ? reason.message
            : '成长数据读取失败，请稍后重试。',
      })
    }
  },

  login() {
    wx.navigateTo({
      url: `/pages/login/index?redirect=${encodeURIComponent(this.currentPath())}`,
    })
  },

  handleAuthFailure(reason: unknown): boolean {
    if (!(reason instanceof ApiError) || reason.status !== 401) return false
    this.requestSequence += 1
    this.setData({ loggedIn: false, growth: null, loading: false, errorMessage: '' })
    wx.showToast({ title: '登录状态已失效', icon: 'none' })
    return true
  },
})
