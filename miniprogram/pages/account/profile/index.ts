import {
  fetchProfile,
  saveAvatar,
  saveProfile,
  type ProfileView,
} from '../../../modules/account/api'
import { ApiError, toErrorMessage } from '../../../shared/api/request'
import { markAvatarUpdated, userAvatarUrl } from '../../../shared/api/avatars'
import { uploadImage } from '../../../shared/api/files'
import { isLoggedIn } from '../../../shared/auth/session'
import { prepareAvatar } from '../../../shared/lib/image'

type ProfileData = {
  loggedIn: boolean
  loading: boolean
  saving: boolean
  errorMessage: string
  saveError: string
  profile: ProfileView | null
  avatarUrl: string
  avatarText: string
  avatarFailed: boolean
  avatarBusy: boolean
  bioDraft: string
  bioCount: number
  bioTooLong: boolean
  dirty: boolean
}

type ProfileCustom = {
  active: boolean
  requestSequence: number
  load(): Promise<void>
  onChooseAvatar(event: { detail: { avatarUrl?: string } }): Promise<void>
  onAvatarError(): void
  onBioInput(event: WxInputEvent): void
  save(): Promise<void>
  clearBio(): void
  persist(bio: string): Promise<void>
  login(): void
  currentPath(): string
  handleAuthFailure(reason: unknown): boolean
}

const BIO_LIMIT = 100

/** 与服务端一致：CRLF/CR 统一为 LF，去掉首尾空白与 BOM；内部换行保留。 */
function normalizeBio(value: string): string {
  return value.replace(/\r\n?/g, '\n').replace(/^[\s\ufeff]+|[\s\ufeff]+$/g, '')
}

function countBio(value: string): number {
  return Array.from(value).length
}

Page<ProfileData, ProfileCustom>({
  data: {
    loggedIn: false,
    loading: false,
    saving: false,
    errorMessage: '',
    saveError: '',
    profile: null,
    avatarUrl: '',
    avatarText: '',
    avatarFailed: false,
    avatarBusy: false,
    bioDraft: '',
    bioCount: 0,
    bioTooLong: false,
    dirty: false,
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
    this.setData({
      loggedIn,
      profile: null,
      avatarUrl: '',
      avatarFailed: false,
      avatarBusy: false,
      bioDraft: '',
      bioCount: 0,
      bioTooLong: false,
      dirty: false,
      loading: false,
      saving: false,
      errorMessage: '',
      saveError: '',
    })
    if (loggedIn) void this.load()
  },

  onUnload() {
    this.active = false
  },

  currentPath(): string {
    return '/pages/account/profile/index'
  },

  async load(): Promise<void> {
    if (!isLoggedIn()) {
      this.setData({ loggedIn: false })
      return
    }
    const sequence = ++this.requestSequence
    this.setData({ loading: true, errorMessage: '', saveError: '' })
    try {
      const profile = await fetchProfile()
      if (!this.active || sequence !== this.requestSequence) return
      this.setData({
        profile,
        // 页面每次进入都重新读取头像地址，失败时回退用户名首字。
        avatarUrl: userAvatarUrl(profile.userId),
        avatarText: Array.from(profile.username)[0] ?? '?',
        avatarFailed: false,
        bioDraft: profile.bio,
        bioCount: countBio(profile.bio),
        bioTooLong: countBio(profile.bio) > BIO_LIMIT,
        dirty: false,
        loading: false,
      })
    } catch (reason) {
      if (!this.active || sequence !== this.requestSequence) return
      this.setData({ loading: false })
      if (this.handleAuthFailure(reason)) return
      this.setData({ errorMessage: toErrorMessage(reason) })
    }
  },

  /**
   * 选择并上传头像。
   *
   * 上传只产生未绑定临时图，真正替换发生在 PUT /api/me/avatar；
   * 任一步失败都保留旧头像，刷新时附带版本号绕过图片缓存。
   */
  async onChooseAvatar(event: { detail: { avatarUrl?: string } }) {
    const rawPath = event.detail.avatarUrl
    if (!rawPath || this.data.avatarBusy) return
    this.setData({ avatarBusy: true, saveError: '' })
    try {
      const compressed = await prepareAvatar(rawPath)
      const image = await uploadImage(compressed, 'AVATAR')
      await saveAvatar(image.fileId)
      if (!this.active) return
      const userId = this.data.profile?.userId ?? ''
      markAvatarUpdated(userId)
      this.setData({
        avatarBusy: false,
        avatarFailed: false,
        avatarUrl: userAvatarUrl(userId),
      })
      wx.showToast({ title: '头像已更新', icon: 'success' })
    } catch (reason) {
      if (!this.active) return
      this.setData({
        avatarBusy: false,
        saveError: reason instanceof Error ? reason.message : '头像更新失败，请稍后重试。',
      })
    }
  },

  onAvatarError() {
    this.setData({ avatarFailed: true })
  },

  onBioInput(event: WxInputEvent) {
    const draft = event.detail.value
    const normalized = normalizeBio(draft)
    const count = countBio(normalized)
    this.setData({
      bioDraft: draft,
      bioCount: count,
      bioTooLong: count > BIO_LIMIT,
      dirty: !!this.data.profile && normalized !== this.data.profile.bio,
      saveError: '',
    })
  },

  async save(): Promise<void> {
    if (!this.data.profile || this.data.saving) return
    if (this.data.bioTooLong) {
      this.setData({ saveError: `简介最多 ${BIO_LIMIT} 字，请删减后再保存。` })
      return
    }
    if (!this.data.dirty) {
      wx.showToast({ title: '简介没有变化', icon: 'none' })
      return
    }
    await this.persist(this.data.bioDraft)
  },

  clearBio() {
    if (!this.data.profile || this.data.saving) return
    if (!this.data.profile.bio) return
    wx.showModal({
      title: '清空简介',
      content: '清空后其他用户将看不到你的个人简介。',
      confirmText: '清空',
      confirmColor: '#b83a34',
      success: (result) => {
        if (!result.confirm) return
        void this.persist('')
      },
    })
  },

  async persist(value: string): Promise<void> {
    if (!this.data.profile || this.data.saving) return
    const bio = normalizeBio(value)
    if (countBio(bio) > BIO_LIMIT) {
      this.setData({ saveError: `简介最多 ${BIO_LIMIT} 字，请删减后再保存。` })
      return
    }
    if (bio === this.data.profile.bio) {
      // 清空已空简介或重复保存同值时不发请求，避免无意义写入。
      this.setData({ bioDraft: bio, bioCount: countBio(bio), bioTooLong: false, dirty: false })
      return
    }
    const sequence = this.requestSequence
    this.setData({ saving: true, saveError: '' })
    try {
      const profile = await saveProfile(bio)
      if (!this.active || sequence !== this.requestSequence) return
      this.setData({
        profile,
        bioDraft: profile.bio,
        bioCount: countBio(profile.bio),
        bioTooLong: false,
        dirty: false,
        saving: false,
      })
      wx.showToast({ title: '简介已保存', icon: 'success' })
    } catch (reason) {
      if (!this.active || sequence !== this.requestSequence) return
      this.setData({ saving: false })
      if (this.handleAuthFailure(reason)) return
      this.setData({ saveError: toErrorMessage(reason) })
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
    this.setData({
      loggedIn: false,
      profile: null,
      avatarUrl: '',
      avatarFailed: false,
      avatarBusy: false,
      bioDraft: '',
      bioCount: 0,
      bioTooLong: false,
      dirty: false,
      loading: false,
      saving: false,
      errorMessage: '',
      saveError: '',
    })
    wx.showToast({ title: '登录状态已失效', icon: 'none' })
    return true
  },
})
