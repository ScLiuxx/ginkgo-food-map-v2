import {
  downloadContributionPhoto,
  fetchContributionDetail,
  type ContributionDetailView,
} from '../../../../modules/restaurant/contribution'
import { ApiError } from '../../../../shared/api/request'
import { isLoggedIn } from '../../../../shared/auth/session'

type PhotoItem = {
  position: number
  path: string
  loading: boolean
  error: string
}

type DetailData = {
  applicationId: string
  loggedIn: boolean
  loading: boolean
  errorMessage: string
  detail: ContributionDetailView | null
  photos: PhotoItem[]
}

type DetailCustom = {
  active: boolean
  requestSequence: number
  load(): Promise<void>
  loadPhotos(): Promise<void>
  patchPhoto(position: number, patch: Partial<PhotoItem>): void
  retryPhoto(event: WxTapEvent): Promise<void>
  previewPhoto(event: WxTapEvent): void
  openRestaurant(): void
  login(): void
  currentPath(): string
  handleAuthFailure(reason: unknown): boolean
}

/**
 * 本人投稿/纠错详情（只读）。
 *
 * 附图是私有资源，先带 Bearer 下载到临时文件再展示；
 * 页面不提供修改、撤回或重提操作，避免出现网页端才具备的半成品入口。
 */
Page<DetailData, DetailCustom>({
  data: {
    applicationId: '',
    loggedIn: false,
    loading: false,
    errorMessage: '',
    detail: null,
    photos: [],
  },

  active: true,
  requestSequence: 0,

  onLoad(query) {
    const id = query.id
    const loggedIn = isLoggedIn()
    this.setData({ loggedIn })
    if (typeof id !== 'string' || !/^[1-9]\d*$/.test(id)) {
      this.setData({ errorMessage: '投稿申请编号无效。' })
      return
    }
    this.setData({ applicationId: id })
    if (loggedIn) void this.load()
  },

  onShow() {
    const loggedIn = isLoggedIn()
    if (loggedIn === this.data.loggedIn) return
    this.requestSequence += 1
    this.setData({ loggedIn, detail: null, photos: [], errorMessage: '', loading: false })
    if (loggedIn) void this.load()
  },

  onUnload() {
    this.active = false
  },

  currentPath(): string {
    return `/pages/account/contributions/detail/index?id=${this.data.applicationId}`
  },

  async load(): Promise<void> {
    if (!this.data.applicationId || !isLoggedIn()) {
      this.setData({ loggedIn: false })
      return
    }
    const sequence = ++this.requestSequence
    this.setData({ loading: true, errorMessage: '', photos: [] })
    try {
      const detail = await fetchContributionDetail(this.data.applicationId)
      if (!this.active || sequence !== this.requestSequence) return
      this.setData({
        detail,
        loading: false,
        photos: detail.photoPositions.map((position) => ({
          position,
          path: '',
          loading: true,
          error: '',
        })),
      })
      void this.loadPhotos()
    } catch (reason) {
      if (!this.active || sequence !== this.requestSequence) return
      this.setData({ loading: false })
      if (this.handleAuthFailure(reason)) return
      this.setData({ errorMessage: toMessage(reason) })
    }
  },

  /** 附图最多 5 张，顺序下载以保证错误能对应到具体位置。 */
  async loadPhotos(): Promise<void> {
    const detail = this.data.detail
    if (!detail) return
    const sequence = this.requestSequence
    for (let index = 0; index < detail.photoPositions.length; index += 1) {
      const position = detail.photoPositions[index]
      try {
        const path = await downloadContributionPhoto(detail.id, position)
        if (!this.active || sequence !== this.requestSequence) return
        this.patchPhoto(position, { path, loading: false, error: '' })
      } catch (reason) {
        if (!this.active || sequence !== this.requestSequence) return
        this.patchPhoto(position, {
          loading: false,
          error: reason instanceof Error ? reason.message : '图片读取失败，请重试。',
        })
      }
    }
  },

  async retryPhoto(event: WxTapEvent): Promise<void> {
    const index = Number(event.currentTarget.dataset.index)
    const photo = this.data.photos[index]
    if (!photo || photo.loading) return
    const detail = this.data.detail
    if (!detail) return
    this.patchPhoto(photo.position, { loading: true, error: '' })
    try {
      const path = await downloadContributionPhoto(detail.id, photo.position)
      if (!this.active) return
      this.patchPhoto(photo.position, { path, loading: false, error: '' })
    } catch (reason) {
      if (!this.active) return
      this.patchPhoto(photo.position, {
        loading: false,
        error: reason instanceof Error ? reason.message : '图片读取失败，请重试。',
      })
    }
  },

  patchPhoto(position: number, patch: Partial<PhotoItem>): void {
    this.setData({
      photos: this.data.photos.map((photo) =>
        photo.position === position ? { ...photo, ...patch } : photo,
      ),
    })
  },

  previewPhoto(event: WxTapEvent): void {
    const index = Number(event.currentTarget.dataset.index)
    const current = this.data.photos[index]
    if (!current || !current.path) return
    const urls = this.data.photos.filter((photo) => photo.path).map((photo) => photo.path)
    if (urls.length === 0) return
    wx.previewImage({ urls, current: current.path })
  },

  openRestaurant(): void {
    const detail = this.data.detail
    if (!detail || !detail.targetAvailable || !detail.restaurantId) return
    wx.navigateTo({
      url: `/pages/restaurant/detail/index?id=${encodeURIComponent(detail.restaurantId)}`,
    })
  },

  login(): void {
    wx.navigateTo({
      url: `/pages/login/index?redirect=${encodeURIComponent(this.currentPath())}`,
    })
  },

  handleAuthFailure(reason: unknown): boolean {
    if (!(reason instanceof ApiError) || reason.status !== 401) return false
    this.requestSequence += 1
    this.setData({
      loggedIn: false,
      detail: null,
      photos: [],
      loading: false,
      errorMessage: '',
    })
    wx.showToast({ title: '登录状态已失效', icon: 'none' })
    return true
  },
})

function toMessage(reason: unknown): string {
  if (reason instanceof Error && reason.message) return reason.message
  return '投稿详情读取失败，请稍后重试。'
}
