import {
  fetchPrivacy,
  saveFavoritesVisibility,
  saveFollowPrivacy,
} from '../../../modules/account/api'
import { ApiError, toErrorMessage } from '../../../shared/api/request'
import { isLoggedIn } from '../../../shared/auth/session'

type PrivacyKey = 'favorites' | 'following' | 'followers'

type PrivacyData = {
  loggedIn: boolean
  loading: boolean
  saving: boolean
  errorMessage: string
  favoritesPublic: boolean
  followingPublic: boolean
  followersPublic: boolean
}

/**
 * 收藏、关注与粉丝名单的可见性设置。
 *
 * 三类名单分属两套接口：收藏单独保存，关注与粉丝必须整体提交两个布尔值。
 * 保存期间禁用全部开关，避免并发 PATCH 让服务端采用非预期的组合；
 * 保存结果不确定时先重新读取，不把未确认状态当作成功。
 */
type PrivacyCustom = {
  /** 读取序号，丢弃切换账号或连续重试产生的迟到响应。 */
  loadSeq: number
  load(): Promise<void>
  retry(): void
  toggle(event: WxSwitchEvent): Promise<void>
  login(): void
}

function isUnauthorized(reason: unknown): boolean {
  return reason instanceof ApiError && reason.status === 401
}

Page<PrivacyData, PrivacyCustom>({
  data: {
    loggedIn: false,
    loading: false,
    saving: false,
    errorMessage: '',
    favoritesPublic: false,
    followingPublic: false,
    followersPublic: false,
  },

  loadSeq: 0,

  onShow() {
    if (!isLoggedIn()) {
      this.setData({
        loggedIn: false,
        loading: false,
        saving: false,
        errorMessage: '',
        favoritesPublic: false,
        followingPublic: false,
        followersPublic: false,
      })
      return
    }
    this.setData({ loggedIn: true })
    void this.load()
  },

  /** 每次进入都重新读取，避免沿用其他设备或网页端已修改的设置。 */
  async load() {
    if (!isLoggedIn()) {
      this.setData({ loggedIn: false })
      return
    }
    const current = this.loadSeq + 1
    this.loadSeq = current
    this.setData({ loading: true, errorMessage: '' })
    try {
      const privacy = await fetchPrivacy()
      if (this.loadSeq !== current) return
      this.setData({
        favoritesPublic: privacy.favoritesPublic,
        followingPublic: privacy.followingPublic,
        followersPublic: privacy.followersPublic,
      })
    } catch (reason) {
      if (this.loadSeq !== current) return
      if (isUnauthorized(reason)) {
        this.setData({
          loggedIn: false,
          favoritesPublic: false,
          followingPublic: false,
          followersPublic: false,
          errorMessage: '登录已失效，请重新登录。',
        })
      } else {
        this.setData({ errorMessage: toErrorMessage(reason) })
      }
    } finally {
      if (this.loadSeq === current) {
        this.setData({ loading: false })
      }
    }
  },

  retry() {
    void this.load()
  },

  /**
   * 保存单个开关。
   *
   * 收藏只提交自身；关注与粉丝共享一个接口，需同时带上另一项的当前值。
   */
  async toggle(event: WxSwitchEvent) {
    if (this.data.saving || this.data.loading) return
    const key = event.currentTarget.dataset.key as PrivacyKey | undefined
    if (key !== 'favorites' && key !== 'following' && key !== 'followers') return
    const value = event.detail.value
    const before = {
      favoritesPublic: this.data.favoritesPublic,
      followingPublic: this.data.followingPublic,
      followersPublic: this.data.followersPublic,
    }
    this.setData({ saving: true, errorMessage: '' })
    try {
      if (key === 'favorites') {
        const saved = await saveFavoritesVisibility(value)
        this.setData({ favoritesPublic: saved })
      } else {
        const followingPublic = key === 'following' ? value : this.data.followingPublic
        const followersPublic = key === 'followers' ? value : this.data.followersPublic
        const saved = await saveFollowPrivacy(followingPublic, followersPublic)
        this.setData({
          followingPublic: saved.followingPublic,
          followersPublic: saved.followersPublic,
        })
      }
    } catch (reason) {
      if (isUnauthorized(reason)) {
        this.setData({
          loggedIn: false,
          favoritesPublic: false,
          followingPublic: false,
          followersPublic: false,
          errorMessage: '登录已失效，请重新登录。',
        })
      } else {
        // 写入结果可能已生效也可能未生效：先恢复上次确认值，再以服务端当前值为准。
        wx.showToast({ title: '保存失败，请重试', icon: 'none' })
        this.setData(before)
        await this.load()
      }
    } finally {
      this.setData({ saving: false })
    }
  },

  login() {
    wx.navigateTo({
      url: `/pages/login/index?redirect=${encodeURIComponent('/pages/account/privacy/index')}`,
    })
  },
})
