import {
  downloadContributionPhoto,
  fetchContributionDetail,
  withdrawContribution,
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
  /** 撤回等写操作进行中；期间不允许重复提交。 */
  busy: boolean
  /** 写操作失败提示；与整页读取失败分开，避免错误卡片覆盖已加载详情。 */
  actionError: string
}

type DetailCustom = {
  active: boolean
  requestSequence: number
  skipNextShowReload: boolean
  load(): Promise<void>
  loadPhotos(): Promise<void>
  patchPhoto(position: number, patch: Partial<PhotoItem>): void
  retryPhoto(event: WxTapEvent): Promise<void>
  previewPhoto(event: WxTapEvent): void
  openRestaurant(): void
  editApplication(): void
  resubmitApplication(): void
  openSubmission(mode: 'edit' | 'resubmit'): void
  withdraw(): void
  performWithdraw(): Promise<void>
  login(): void
  currentPath(): string
  handleAuthFailure(reason: unknown): boolean
}

/**
 * 本人投稿/纠错详情。
 *
 * 附图是私有资源，先带 Bearer 下载到临时文件再展示；
 * 待审申请可整体修改或撤回，被驳回申请可修改后重新提交。
 * 详情接口只返回附图位置，修改/重提时无法沿用原文件编号，界面会明确要求重新上传。
 */
Page<DetailData, DetailCustom>({
  data: {
    applicationId: '',
    loggedIn: false,
    loading: false,
    errorMessage: '',
    detail: null,
    photos: [],
    busy: false,
    actionError: '',
  },

  active: true,
  requestSequence: 0,
  skipNextShowReload: false,

  onLoad(query) {
    const id = query.id
    const loggedIn = isLoggedIn()
    this.setData({ loggedIn })
    if (typeof id !== 'string' || !/^[1-9]\d*$/.test(id)) {
      this.setData({ errorMessage: '投稿申请编号无效。' })
      return
    }
    this.setData({ applicationId: id })
    if (loggedIn) {
      // onLoad 后紧跟的 onShow 不应重复请求同一份详情。
      this.skipNextShowReload = true
      void this.load()
    }
  },

  onShow() {
    const loggedIn = isLoggedIn()
    if (loggedIn !== this.data.loggedIn) {
      this.requestSequence += 1
      this.setData({
        loggedIn,
        detail: null,
        photos: [],
        errorMessage: '',
        loading: false,
        busy: false,
        actionError: '',
      })
      if (loggedIn) void this.load()
      return
    }
    if (this.skipNextShowReload) {
      this.skipNextShowReload = false
      return
    }
    // 从修改/重提页面返回时重新读取，确保状态与内容不是旧快照。
    if (loggedIn && this.data.applicationId) void this.load()
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
    this.setData({ loading: true, errorMessage: '', photos: [], actionError: '' })
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

  /** 待审申请进入修改模式；表单按申请快照回填，附图需重新上传。 */
  editApplication(): void {
    const detail = this.data.detail
    if (!detail || !detail.canEdit || this.data.busy) return
    this.openSubmission('edit')
  },

  /** 被驳回申请进入重提模式；成功后服务端会创建新的待审申请。 */
  resubmitApplication(): void {
    const detail = this.data.detail
    if (!detail || !detail.canResubmit || this.data.busy) return
    this.openSubmission('resubmit')
  },

  openSubmission(mode: 'edit' | 'resubmit'): void {
    wx.navigateTo({
      url:
        `/pages/submission/index?mode=${mode}` +
        `&requestId=${encodeURIComponent(this.data.applicationId)}`,
    })
  },

  /** 撤回不可恢复，必须显式确认；重复撤回由服务端保持幂等。 */
  withdraw(): void {
    const detail = this.data.detail
    if (!detail || !detail.canEdit || this.data.busy) return
    wx.showModal({
      title: '撤回申请',
      content: '撤回后该申请不可恢复；如需再次提交，请重新填写。原申请内容不会修改公开店铺资料。',
      confirmText: '撤回申请',
      confirmColor: '#b83a34',
      success: (result) => {
        if (result.confirm) void this.performWithdraw()
      },
    })
  },

  async performWithdraw(): Promise<void> {
    const detail = this.data.detail
    if (!detail || this.data.busy) return
    this.setData({ busy: true, actionError: '' })
    try {
      await withdrawContribution(detail.id)
      wx.showToast({ title: '已撤回申请', icon: 'success' })
      await this.load()
    } catch (reason) {
      if (!this.handleAuthFailure(reason)) {
        this.setData({ actionError: toMessage(reason) })
      }
    } finally {
      this.setData({ busy: false })
    }
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
