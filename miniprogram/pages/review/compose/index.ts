import { ApiError, toErrorMessage } from '../../../shared/api/request'
import { isLoggedIn } from '../../../shared/auth/session'
import {
  MAX_REVIEW_BODY,
  MAX_REVIEW_PHOTOS,
  cancelReviewPhoto,
  createRequestId,
  isValidScores,
  normalizeReviewBody,
  prepareReviewPhoto,
  publishReview,
  reviewBodyLength,
  uploadReviewPhoto,
  type ReviewScores,
  type ScoreKey,
} from '../../../modules/review/compose'

type PhotoStatus = 'uploading' | 'ready' | 'failed'

type DraftPhoto = {
  key: number
  /** 本地预览路径；压缩后会被替换，但始终不依赖远端图片地址。 */
  previewPath: string
  /** 用户选择的原始临时文件，重试压缩时仍从这里开始。 */
  sourcePath: string
  fileId: string
  status: PhotoStatus
  error: string
}

type ScoreRow = {
  key: ScoreKey
  label: string
  value: number
}

type ComposeData = {
  restaurantId: string
  restaurantName: string
  loggedIn: boolean
  scoreRows: ScoreRow[]
  scores: ReviewScores
  recommended: boolean
  body: string
  bodyCount: number
  photos: DraftPhoto[]
  uploading: boolean
  submitting: boolean
  errorMessage: string
  maxPhotos: number
  maxBody: number
}

type ComposeCustom = {
  /** 当前发布请求号与内容指纹；仅用于同一内容的幂等重试。 */
  requestId: string
  submittedContent: string
  startUpload(key: number): Promise<void>
  changeScore(event: WxSliderEvent): void
  changeRecommended(event: WxSwitchEvent): void
  changeBody(event: WxInputEvent): void
  choosePhotos(): void
  retryPhoto(event: WxTapEvent): void
  removePhoto(event: WxTapEvent): void
  previewPhotos(event: WxTapEvent): void
  submit(): Promise<void>
  refreshUploadingState(): void
  patchPhoto(key: number, patch: Partial<DraftPhoto>): void
  login(): void
}

const SCORE_LABELS: Array<{ key: ScoreKey; label: string }> = [
  { key: 'taste', label: '口味' },
  { key: 'value', label: '性价比' },
  { key: 'service', label: '服务' },
  { key: 'ambience', label: '环境' },
]

/** 评分滑杆可能出现浮点误差，统一吸附到 0.5 步进后再提交。 */
function snapScore(value: number): number {
  const clamped = Math.min(10, Math.max(0, Number(value) || 0))
  return Math.round(clamped * 2) / 2
}

function buildRows(scores: ReviewScores): ScoreRow[] {
  return SCORE_LABELS.map((item) => ({ ...item, value: scores[item.key] }))
}

/** 上传中的照片只保留本地路径；失败照片明确显示，不能静默作为成功提交。 */
function defaultScores(): ReviewScores {
  return { taste: 8, value: 8, service: 8, ambience: 8 }
}

let photoSeq = 0
/** 页面卸载后迟到的压缩/上传结果不能再写回界面。 */
let pageGeneration = 0

/**
 * 写点评。
 *
 * 从店铺详情进入时固定评价对象，不支持在提交前换店，避免误评。
 * 正文与评分只保存在页面内存；照片先压缩上传换取 fileId，发布时与点评同事务绑定。
 * 相同内容重试复用发布请求号，正文、评分或图片变化后换新请求号，避免幂等冲突。
 */
Page<ComposeData, ComposeCustom>({
  data: {
    restaurantId: '',
    restaurantName: '',
    loggedIn: false,
    scoreRows: buildRows(defaultScores()),
    scores: defaultScores(),
    recommended: true,
    body: '',
    bodyCount: 0,
    photos: [],
    uploading: false,
    submitting: false,
    errorMessage: '',
    maxPhotos: MAX_REVIEW_PHOTOS,
    maxBody: MAX_REVIEW_BODY,
  },

  onLoad(query) {
    pageGeneration++
    const rawId = query.id ? decodeURIComponent(query.id) : ''
    const name = query.name ? decodeURIComponent(query.name) : ''
    const loggedIn = isLoggedIn()
    if (!/^[1-9]\d*$/.test(rawId)) {
      this.setData({ loggedIn, errorMessage: '店铺编号无效，无法写点评。' })
      return
    }
    this.setData({ restaurantId: rawId, restaurantName: name, loggedIn })
    wx.setNavigationBarTitle({ title: '写点评' })
  },

  onShow() {
    this.setData({ loggedIn: isLoggedIn() })
  },

  onUnload() {
    pageGeneration++
    // 未发布的临时图由服务端 24 小时到期清理；这里不发起删除，避免与进行中的发布竞争。
  },

  changeScore(event: WxSliderEvent) {
    const key = String(event.currentTarget.dataset.key ?? '') as ScoreKey
    if (!['taste', 'value', 'service', 'ambience'].includes(key)) return
    const value = snapScore(event.detail.value)
    const scores = { ...this.data.scores, [key]: value } as ReviewScores
    this.setData({ scores, scoreRows: buildRows(scores), errorMessage: '' })
  },

  changeRecommended(event: WxSwitchEvent) {
    this.setData({ recommended: Boolean(event.detail.value) })
  },

  changeBody(event: WxInputEvent) {
    const body = event.detail.value ?? ''
    this.setData({ body, bodyCount: reviewBodyLength(body) })
  },

  choosePhotos() {
    if (this.data.submitting) return
    const remaining = MAX_REVIEW_PHOTOS - this.data.photos.length
    if (remaining <= 0) return
    const generation = pageGeneration
    wx.chooseMedia({
      count: remaining,
      mediaType: ['image'],
      sizeType: ['compressed'],
      sourceType: ['album', 'camera'],
      success: (result) => {
        if (generation !== pageGeneration) return
        const files = (result.tempFiles ?? []).filter((file) => Boolean(file.tempFilePath))
        if (!files.length) return
        const appended = files.slice(0, remaining).map((file): DraftPhoto => {
          const key = ++photoSeq
          return {
            key,
            previewPath: file.tempFilePath,
            sourcePath: file.tempFilePath,
            fileId: '',
            status: 'uploading',
            error: '',
          }
        })
        this.setData({ photos: this.data.photos.concat(appended), errorMessage: '' })
        this.refreshUploadingState()
        appended.forEach((photo) => void this.startUpload(photo.key))
      },
    })
  },

  async startUpload(key: number) {
    const generation = pageGeneration
    const photo = this.data.photos.find((item) => item.key === key)
    if (!photo) return
    this.patchPhoto(key, { status: 'uploading', error: '' })
    this.refreshUploadingState()
    try {
      const prepared = await prepareReviewPhoto(photo.sourcePath)
      if (generation !== pageGeneration) return
      this.patchPhoto(key, { previewPath: prepared })
      const uploaded = await uploadReviewPhoto(prepared)
      if (generation !== pageGeneration) return
      this.patchPhoto(key, { status: 'ready', fileId: uploaded.fileId, error: '' })
    } catch (reason) {
      if (generation !== pageGeneration) return
      this.patchPhoto(key, { status: 'failed', error: toErrorMessage(reason) })
    } finally {
      if (generation === pageGeneration) this.refreshUploadingState()
    }
  },

  retryPhoto(event: WxTapEvent) {
    if (this.data.submitting) return
    const index = Number(event.currentTarget.dataset.index)
    const photo = this.data.photos[index]
    if (!photo || photo.status !== 'failed') return
    void this.startUpload(photo.key)
  },

  removePhoto(event: WxTapEvent) {
    if (this.data.submitting) return
    const index = Number(event.currentTarget.dataset.index)
    const photo = this.data.photos[index]
    if (!photo) return
    this.setData({ photos: this.data.photos.filter((item) => item.key !== photo.key) })
    this.refreshUploadingState()
    if (photo.fileId) {
      // 取消未绑定图片失败不影响界面移除，临时文件到期后由服务端清理。
      void cancelReviewPhoto(photo.fileId).catch(() => undefined)
    }
  },

  previewPhotos(event: WxTapEvent) {
    const index = Number(event.currentTarget.dataset.index)
    const current = this.data.photos[index]
    const urls = this.data.photos.map((item) => item.previewPath)
    if (!current || !urls.length) return
    wx.previewImage({ urls, current: current.previewPath })
  },

  async submit() {
    if (this.data.submitting) return
    if (!this.data.loggedIn) {
      this.setData({ errorMessage: '请先登录后再发布点评。' })
      return
    }
    if (this.data.uploading || this.data.photos.some((item) => item.status !== 'ready')) {
      this.setData({ errorMessage: '照片尚未全部上传成功，请重试或移除失败照片。' })
      return
    }
    const body = normalizeReviewBody(this.data.body)
    const length = reviewBodyLength(this.data.body)
    if (length < 5 || length > MAX_REVIEW_BODY) {
      this.setData({ errorMessage: `请填写 5–${MAX_REVIEW_BODY} 字的点评正文。` })
      return
    }
    if (!isValidScores(this.data.scores)) {
      this.setData({ errorMessage: '评分需为 0–10 且步进 0.5。' })
      return
    }

    const photoIds = this.data.photos.map((item) => item.fileId)
    const content = JSON.stringify([
      this.data.restaurantId,
      this.data.scores,
      this.data.recommended,
      body,
      photoIds,
    ])
    // 内容未变的重试沿用原请求号；改动后换新编号，避免服务端判定内容冲突。
    if (this.submittedContent && this.submittedContent !== content) {
      this.requestId = createRequestId()
    }
    this.submittedContent = content
    if (!this.requestId) this.requestId = createRequestId()

    this.setData({ submitting: true, errorMessage: '' })
    try {
      const receipt = await publishReview(this.data.restaurantId, {
        clientRequestId: this.requestId,
        scores: this.data.scores,
        recommended: this.data.recommended,
        body,
        photoIds,
      })
      this.submittedContent = ''
      this.requestId = ''
      wx.showToast({ title: receipt.replayed ? '点评已存在' : '点评已发布', icon: 'success' })
      setTimeout(() => wx.navigateBack(), 600)
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) {
        this.setData({ loggedIn: false, errorMessage: '登录已失效，请重新登录后重试。' })
      } else {
        this.setData({ errorMessage: toErrorMessage(reason) })
      }
      this.setData({ submitting: false })
    }
  },

  refreshUploadingState() {
    this.setData({ uploading: this.data.photos.some((item) => item.status === 'uploading') })
  },

  /** 按稳定 key 更新照片，删除或重排后不会把回执写到别的照片上。 */
  patchPhoto(key: number, patch: Partial<DraftPhoto>) {
    this.setData({
      photos: this.data.photos.map((item) => (item.key === key ? { ...item, ...patch } : item)),
    })
  },

  login() {
    const id = this.data.restaurantId
    const name = this.data.restaurantName
    const redirect = `/pages/review/compose/index?id=${encodeURIComponent(id)}${
      name ? `&name=${encodeURIComponent(name)}` : ''
    }`
    wx.navigateTo({ url: `/pages/login/index?redirect=${encodeURIComponent(redirect)}` })
  },

  /** 请求号与内容指纹只保留在当前页面实例，不写入本地存储。 */
  requestId: '',
  submittedContent: '',
})

// Page 自定义字段需要在实例上可写，声明在 Page 类型中便于 TS 检查。
