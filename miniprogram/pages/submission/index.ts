import { fetchCategories, fetchRestaurantDetail } from '../../modules/restaurant/api'
import {
  MAX_SUBMISSION_PHOTOS,
  buildSubmissionPayload,
  submitNewRestaurant,
  submitRestaurantCorrection,
  toCorrectionDraft,
  validateSubmissionDraft,
  type RestaurantType,
  type SubmissionBusinessStatus,
  type SubmissionDraft,
  type SubmissionPriceKind,
  type TriState,
} from '../../modules/restaurant/submission'
import {
  fetchContributionEditSource,
  resubmitContribution,
  updateContribution,
} from '../../modules/restaurant/contribution'
import { cancelImage, uploadImage } from '../../shared/api/files'
import { ApiError, toErrorMessage } from '../../shared/api/request'
import { isLoggedIn } from '../../shared/auth/session'
import { createRequestId, isLongId } from '../../shared/lib/id'
import { prepareImage } from '../../shared/lib/image'

type PhotoStatus = 'uploading' | 'ready' | 'failed'
type RequestMode = 'edit' | 'resubmit'
type SubmissionMode = 'new' | 'correction' | RequestMode

type DraftPhoto = {
  key: number
  /** 本地预览路径；压缩后会被替换，不依赖远端图片地址。 */
  previewPath: string
  sourcePath: string
  fileId: string
  status: PhotoStatus
  error: string
}

type SubmissionData = {
  loggedIn: boolean
  mode: SubmissionMode
  /** 纠错、修改和重提都会先读取已有资料，复用同一块来源状态。 */
  isCorrection: boolean
  sourceLoading: boolean
  sourceError: string
  heroTitle: string
  heroCopy: string
  loginTitle: string
  loginCopy: string
  photoSectionLabel: string
  photoHint: string
  submitLabel: string
  submitHint: string
  name: string
  typeLabels: string[]
  typeIndex: number
  priceLabels: string[]
  businessLabels: string[]
  triLabels: string[]
  categoryIndex: number
  categoryLabels: string[]
  categoryCodes: string[]
  categoriesLoading: boolean
  priceIndex: number
  priceText: string
  address: string
  latitude: string
  longitude: string
  locationText: string
  locationConfirmed: boolean
  businessIndex: number
  dineIndex: number
  deliveryIndex: number
  tagsText: string
  photos: DraftPhoto[]
  uploading: boolean
  submitting: boolean
  errorMessage: string
  maxPhotos: number
}

type SubmissionCustom = {
  requestId: string
  /** 修改/重提模式下的申请编号；新投稿与纠错为空。 */
  applicationId: string
  requestMode: '' | RequestMode
  submittedContent: string
  /** 纠错目标店铺；新店投稿为空。 */
  restaurantId: string
  /** 分类尚未加载时暂存原分类编码，加载完成后按编码恢复选择。 */
  pendingCategoryCode: string
  loadCategories(): Promise<void>
  loadSource(): Promise<void>
  loadRequestSource(): Promise<void>
  retrySource(): void
  applyDraft(draft: SubmissionDraft): void
  selectCategoryCode(code: string): void
  confirmLocation(): void
  changeName(event: WxInputEvent): void
  changeType(event: WxPickerEvent): void
  changeCategory(event: WxPickerEvent): void
  changePriceKind(event: WxPickerEvent): void
  changePrice(event: WxInputEvent): void
  changeAddress(event: WxInputEvent): void
  chooseLocation(): void
  changeBusiness(event: WxPickerEvent): void
  changeDineIn(event: WxPickerEvent): void
  changeDelivery(event: WxPickerEvent): void
  changeTags(event: WxInputEvent): void
  choosePhotos(): void
  startUpload(key: number): Promise<void>
  retryPhoto(event: WxTapEvent): void
  removePhoto(event: WxTapEvent): void
  previewPhotos(event: WxTapEvent): void
  submit(): Promise<void>
  refreshUploadingState(): void
  patchPhoto(key: number, patch: Partial<DraftPhoto>): void
  buildDraft(): SubmissionDraft
  actionName(): string
  login(): void
}

const TYPE_LABELS = ['普通餐厅', '食堂']
const TYPE_VALUES: RestaurantType[] = ['REGULAR', 'CANTEEN']
const PRICE_LABELS = ['未知', '精确人均', '约人均']
const PRICE_VALUES: SubmissionPriceKind[] = ['UNKNOWN', 'EXACT', 'ESTIMATE']
const BUSINESS_LABELS = ['营业中', '已歇业']
const BUSINESS_VALUES: SubmissionBusinessStatus[] = ['OPERATING', 'CLOSED']
const TRI_LABELS = ['未知', '支持', '不支持']
const TRI_VALUES: TriState[] = [null, true, false]

const MAX_NAME = 40
const MAX_ADDRESS = 80
const MAX_TAGS_TEXT = 160

let photoSeq = 0
/** 页面卸载后迟到的分类、压缩或上传结果不能再写回界面。 */
let pageGeneration = 0

/** 按码点截断，避免把 emoji 等代理对切成半个字符。 */
function truncateText(value: string, limit: number): string {
  const trimmed = value.trim()
  const chars = Array.from(trimmed)
  return chars.length > limit ? chars.slice(0, limit).join('') : trimmed
}

/**
 * 新店投稿与资料纠错。
 *
 * 只提交待审核申请，不直接创建公开店铺或覆盖现有资料；所有字段在页面内存中编辑，
 * 不写入本地存储，也不读取微信头像、昵称或手机号。
 * 位置通过微信地图选择并显式确认，避免用户只填写文字地址导致坐标缺失；
 * 纠错模式会带入公开资料，但历史坐标仍需用户重新确认。
 */
Page<SubmissionData, SubmissionCustom>({
  data: {
    loggedIn: false,
    mode: 'new',
    isCorrection: false,
    sourceLoading: false,
    sourceError: '',
    heroTitle: '新店投稿',
    heroCopy: '提交后进入审核队列，通过后才会出现在找店列表。请尽量填写真实资料并在地图中确认位置。',
    loginTitle: '登录后提交投稿',
    loginCopy: '投稿会记录当前账号，不读取微信头像、昵称或手机号；草稿只保留在当前页面。',
    photoSectionLabel: '照片',
    photoHint: '最多 5 张，上传前自动压缩；失败照片需重试或移除后才能提交。',
    submitLabel: '提交审核',
    submitHint: '提交只代表申请已受理，审核结果请到“我的投稿”查看。',
    name: '',
    typeLabels: TYPE_LABELS,
    typeIndex: 0,
    priceLabels: PRICE_LABELS,
    businessLabels: BUSINESS_LABELS,
    triLabels: TRI_LABELS,
    categoryIndex: 0,
    categoryLabels: ['请选择分类'],
    categoryCodes: [],
    categoriesLoading: false,
    priceIndex: 0,
    priceText: '',
    address: '',
    latitude: '',
    longitude: '',
    locationText: '',
    locationConfirmed: false,
    businessIndex: 0,
    dineIndex: 0,
    deliveryIndex: 0,
    tagsText: '',
    photos: [],
    uploading: false,
    submitting: false,
    errorMessage: '',
    maxPhotos: MAX_SUBMISSION_PHOTOS,
  },

  requestId: '',
  applicationId: '',
  requestMode: '',
  submittedContent: '',
  restaurantId: '',
  pendingCategoryCode: '',

  onLoad(query) {
    pageGeneration++
    const restaurantId = query.id ? decodeURIComponent(query.id) : ''
    const requestMode: '' | RequestMode =
      query.mode === 'edit' || query.mode === 'resubmit' ? query.mode : ''
    const applicationId = query.requestId ? decodeURIComponent(query.requestId) : ''
    const loggedIn = isLoggedIn()
    if (requestMode) {
      // 修改/重提复用投稿表单，但以申请快照为准，不读取公开店铺资料。
      this.requestMode = requestMode
      this.applicationId = isLongId(applicationId) ? applicationId : ''
      const editing = requestMode === 'edit'
      this.setData({
        loggedIn,
        mode: requestMode,
        isCorrection: true,
        heroTitle: editing ? '修改待审核申请' : '重新提交申请',
        heroCopy: editing
          ? '修改会整体替换当前待审内容，并重新进入审核队列；通过前不会改动公开资料。'
          : '将基于被驳回的内容创建新的待审申请，原驳回记录与原因保持不变。',
        loginTitle: editing ? '登录后修改申请' : '登录后重新提交',
        loginCopy: '操作会记录当前账号，不读取微信头像、昵称或手机号；草稿只保留在当前页面。',
        photoSectionLabel: '本次附图',
        photoHint:
          '原申请附图无法自动沿用，修改和重提都需要重新选择上传；不选则本次申请不含附图（最多 5 张）。',
        submitLabel: editing ? '保存修改' : '重新提交审核',
        submitHint: editing
          ? '保存后申请回到待审核状态，审核结果请到“我的投稿”查看。'
          : '重新提交后会生成新的待审申请，可在“我的投稿”查看进度。',
        sourceError: this.applicationId ? '' : '申请编号无效，请返回投稿记录后重试。',
      })
      wx.setNavigationBarTitle({ title: editing ? '修改申请' : '重新提交' })
      if (this.applicationId) void this.loadRequestSource()
    } else if (restaurantId) {
      // 纠错复用同一表单：先按公开资料回填，再等待用户核对位置后提交审核。
      this.restaurantId = isLongId(restaurantId) ? restaurantId : ''
      this.setData({
        loggedIn,
        mode: 'correction',
        isCorrection: true,
        heroTitle: '补充 / 纠错',
        heroCopy: '已带入当前店铺资料，只需修改有误或缺失的内容。提交后由管理员审核，通过前不会改动公开资料。',
        loginTitle: '登录后提交纠错',
        loginCopy: '纠错会记录当前账号，不读取微信头像、昵称或手机号；资料只保留在当前页面。',
        photoSectionLabel: '本次新增图片',
        photoHint: '已有照片无需重复上传；本次新增图片审核通过后追加到相册。最多 5 张，上传前自动压缩；失败照片需重试或移除后才能提交。',
        sourceError: this.restaurantId ? '' : '店铺编号无效，请返回详情后重试。',
      })
      wx.setNavigationBarTitle({ title: '补充 / 纠错' })
      if (this.restaurantId) void this.loadSource()
    } else {
      this.setData({ loggedIn })
      wx.setNavigationBarTitle({ title: '新店投稿' })
    }
    // 分类是公开数据，未登录也先读取，登录后可直接继续填写。
    void this.loadCategories()
  },

  onShow() {
    const loggedIn = isLoggedIn()
    if (loggedIn !== this.data.loggedIn) this.setData({ loggedIn })
  },

  onUnload() {
    pageGeneration++
    // 未发布的临时图由服务端 24 小时到期清理，这里不发起删除以免与提交竞争。
  },

  /**
   * 读取启用分类。
   *
   * 服务端返回的排序只用于展示，草稿以 code 记录当前选择；
   * 刷新分类后按 code 恢复，避免选项变化把用户选择错位到别的分类。
   */
  async loadCategories(): Promise<void> {
    const generation = pageGeneration
    this.setData({ categoriesLoading: true })
    try {
      const rows = await fetchCategories()
      if (generation !== pageGeneration) return
      const enabled = (Array.isArray(rows) ? rows : [])
        .filter(
          (row) =>
            row &&
            typeof row.code === 'string' &&
            row.code.length > 0 &&
            typeof row.name === 'string' &&
            row.name.length > 0 &&
            row.enabled === true,
        )
        .slice()
        .sort((left, right) => (Number(left.sortOrder) || 0) - (Number(right.sortOrder) || 0))
      const codes = enabled.map((row) => row.code)
      // 纠错回填的分类优先于页面上次的临时选择，避免异步加载顺序把原分类覆盖掉。
      const previous =
        this.pendingCategoryCode ||
        this.data.categoryCodes[this.data.categoryIndex] ||
        ''
      const restored = previous ? codes.indexOf(previous) + 1 : 0
      this.setData({
        categoryCodes: codes,
        categoryLabels: ['请选择分类'].concat(enabled.map((row) => row.name)),
        categoryIndex: restored > 0 ? restored : 0,
        categoriesLoading: false,
      })
      this.pendingCategoryCode = ''
    } catch (reason) {
      if (generation !== pageGeneration) return
      this.setData({ categoriesLoading: false, errorMessage: toErrorMessage(reason) })
    }
  },

  /**
   * 读取纠错目标的公开资料并回填表单。
   *
   * 只接受与请求编号一致的响应；读取失败时保留空表单并阻止提交，
   * 不能让用户在没有完整快照的情况下提交，覆盖掉未展示的字段。
   */
  async loadSource(): Promise<void> {
    const id = this.restaurantId
    if (!id) return
    const generation = pageGeneration
    this.setData({ sourceLoading: true, sourceError: '' })
    try {
      const row = await fetchRestaurantDetail(id)
      if (generation !== pageGeneration) return
      if (!row || row.id !== id) throw new Error('店铺资料无法确认，请返回详情后重试。')
      this.applyDraft(toCorrectionDraft(row))
    } catch (reason) {
      if (generation !== pageGeneration) return
      this.setData({ sourceLoading: false, sourceError: toErrorMessage(reason) })
    }
  },

  /**
   * 读取本人申请快照用于修改或重提。
   *
   * 状态在服务端再次校验；若页面停留期间申请已被审核或撤回，
   * 这里会返回明确提示，而不是让用户在过期表单里继续填写。
   */
  async loadRequestSource(): Promise<void> {
    const id = this.applicationId
    const mode = this.requestMode
    if (!id || !mode) return
    const generation = pageGeneration
    this.setData({ sourceLoading: true, sourceError: '' })
    try {
      const source = await fetchContributionEditSource(id, mode)
      if (generation !== pageGeneration) return
      this.applyDraft(source.draft)
    } catch (reason) {
      if (generation !== pageGeneration) return
      this.setData({ sourceLoading: false, sourceError: toErrorMessage(reason) })
    }
  },

  /** 重新读取失败后按当前模式选择正确的来源，避免重提页面误读公开店铺。 */
  retrySource(): void {
    if (this.requestMode) {
      void this.loadRequestSource()
    } else {
      void this.loadSource()
    }
  },

  /** 把已有快照写入表单；编码匹配和坐标确认由后续交互完成。 */
  applyDraft(draft: SubmissionDraft): void {
    const typeIndex = TYPE_VALUES.indexOf(draft.restaurantType)
    const priceIndex = PRICE_VALUES.indexOf(draft.priceKind)
    const businessIndex = BUSINESS_VALUES.indexOf(draft.businessStatus)
    const dineIndex = TRI_VALUES.indexOf(draft.supportsDineIn)
    const deliveryIndex = TRI_VALUES.indexOf(draft.supportsDelivery)
    this.pendingCategoryCode = draft.categoryCode
    this.setData({
      name: draft.name,
      typeIndex: typeIndex >= 0 ? typeIndex : 0,
      categoryIndex: 0,
      priceIndex: priceIndex >= 0 ? priceIndex : 0,
      priceText: draft.priceText,
      address: draft.address,
      latitude: draft.latitude,
      longitude: draft.longitude,
      locationText:
        draft.latitude && draft.longitude ? `${draft.latitude}, ${draft.longitude}` : '',
      locationConfirmed: draft.locationConfirmed,
      businessIndex: businessIndex >= 0 ? businessIndex : 0,
      dineIndex: dineIndex >= 0 ? dineIndex : 0,
      deliveryIndex: deliveryIndex >= 0 ? deliveryIndex : 0,
      tagsText: draft.tagsText,
      sourceLoading: false,
      sourceError: '',
    })
    this.selectCategoryCode(draft.categoryCode)
  },

  /** 分类可能先于资料加载；编码未匹配时保留待选项，不把选择错位到其他分类。 */
  selectCategoryCode(code: string): void {
    if (!code) {
      this.setData({ categoryIndex: 0 })
      return
    }
    const index = this.data.categoryCodes.indexOf(code)
    this.setData({ categoryIndex: index >= 0 ? index + 1 : 0 })
    // 已匹配到当前分类时不再需要等待分类加载；未匹配则保留待选项供后续恢复。
    if (!code || index >= 0) this.pendingCategoryCode = ''
  },

  /** 纠错带入的历史坐标必须由用户主动核对，不能默认视为本人已确认。 */
  confirmLocation(): void {
    if (!this.data.latitude || !this.data.longitude) return
    this.setData({ locationConfirmed: true, errorMessage: '' })
  },

  changeName(event: WxInputEvent): void {
    this.setData({ name: event.detail.value ?? '', errorMessage: '' })
  },

  changeType(event: WxPickerEvent): void {
    const index = Number(event.detail.value)
    if (!Number.isInteger(index) || index < 0 || index >= TYPE_VALUES.length) return
    // 食堂没有分类，切换后清空分类选择，避免提交时被服务端拒绝。
    this.setData({ typeIndex: index, categoryIndex: 0, errorMessage: '' })
  },

  changeCategory(event: WxPickerEvent): void {
    const index = Number(event.detail.value)
    if (!Number.isInteger(index) || index < 0 || index >= this.data.categoryLabels.length) return
    this.setData({ categoryIndex: index, errorMessage: '' })
  },

  changePriceKind(event: WxPickerEvent): void {
    const index = Number(event.detail.value)
    if (!Number.isInteger(index) || index < 0 || index >= PRICE_VALUES.length) return
    this.setData({
      priceIndex: index,
      priceText: PRICE_VALUES[index] === 'UNKNOWN' ? '' : this.data.priceText,
      errorMessage: '',
    })
  },

  changePrice(event: WxInputEvent): void {
    const digits = (event.detail.value ?? '').replace(/[^\d]/g, '')
    this.setData({ priceText: digits.slice(0, 4), errorMessage: '' })
  },

  changeAddress(event: WxInputEvent): void {
    this.setData({ address: event.detail.value ?? '', errorMessage: '' })
  },

  /**
   * 通过微信地图选择位置。
   *
   * chooseLocation 返回 GCJ-02 坐标，与后端和地图页一致；
   * 地址为空时用所选地点补全，已填写则保留用户输入，避免覆盖门牌号。
   */
  chooseLocation(): void {
    wx.chooseLocation({
      success: (result) => {
        const latitude = Number(result.latitude)
        const longitude = Number(result.longitude)
        if (
          !Number.isFinite(latitude) ||
          !Number.isFinite(longitude) ||
          latitude < -90 ||
          latitude > 90 ||
          longitude < -180 ||
          longitude > 180
        ) {
          this.setData({ errorMessage: '未能读取所选位置，请重新选择。' })
          return
        }
        const picked = [result.name, result.address].filter(Boolean).join(' · ')
        const address = this.data.address.trim()
          ? this.data.address
          : truncateText(result.address || result.name || '', MAX_ADDRESS)
        this.setData({
          latitude: latitude.toFixed(6),
          longitude: longitude.toFixed(6),
          locationText: picked || `${latitude.toFixed(6)}, ${longitude.toFixed(6)}`,
          locationConfirmed: true,
          address,
          errorMessage: '',
        })
      },
      fail: (error) => {
        if (error.errMsg && error.errMsg.indexOf('cancel') >= 0) return
        this.setData({
          errorMessage: '未能打开位置选择，请检查定位权限后重试。',
        })
      },
    })
  },

  changeBusiness(event: WxPickerEvent): void {
    const index = Number(event.detail.value)
    if (!Number.isInteger(index) || index < 0 || index >= BUSINESS_VALUES.length) return
    this.setData({ businessIndex: index, errorMessage: '' })
  },

  changeDineIn(event: WxPickerEvent): void {
    const index = Number(event.detail.value)
    if (!Number.isInteger(index) || index < 0 || index >= TRI_VALUES.length) return
    this.setData({ dineIndex: index, errorMessage: '' })
  },

  changeDelivery(event: WxPickerEvent): void {
    const index = Number(event.detail.value)
    if (!Number.isInteger(index) || index < 0 || index >= TRI_VALUES.length) return
    this.setData({ deliveryIndex: index, errorMessage: '' })
  },

  changeTags(event: WxInputEvent): void {
    this.setData({ tagsText: event.detail.value ?? '', errorMessage: '' })
  },

  choosePhotos(): void {
    if (this.data.submitting) return
    const remaining = MAX_SUBMISSION_PHOTOS - this.data.photos.length
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

  async startUpload(key: number): Promise<void> {
    const generation = pageGeneration
    const photo = this.data.photos.find((item) => item.key === key)
    if (!photo) return
    this.patchPhoto(key, { status: 'uploading', error: '' })
    this.refreshUploadingState()
    try {
      const prepared = await prepareImage(photo.sourcePath)
      if (generation !== pageGeneration) return
      this.patchPhoto(key, { previewPath: prepared })
      // 投稿图与点评图用途不同，必须使用 RESTAURANT，避免被绑定到错误业务。
      const uploaded = await uploadImage(prepared, 'RESTAURANT')
      if (generation !== pageGeneration) return
      this.patchPhoto(key, { status: 'ready', fileId: uploaded.fileId, error: '' })
    } catch (reason) {
      if (generation !== pageGeneration) return
      this.patchPhoto(key, { status: 'failed', error: toErrorMessage(reason) })
    } finally {
      if (generation === pageGeneration) this.refreshUploadingState()
    }
  },

  retryPhoto(event: WxTapEvent): void {
    if (this.data.submitting) return
    const index = Number(event.currentTarget.dataset.index)
    const photo = this.data.photos[index]
    if (!photo || photo.status !== 'failed') return
    void this.startUpload(photo.key)
  },

  removePhoto(event: WxTapEvent): void {
    if (this.data.submitting) return
    const index = Number(event.currentTarget.dataset.index)
    const photo = this.data.photos[index]
    if (!photo) return
    this.setData({ photos: this.data.photos.filter((item) => item.key !== photo.key) })
    this.refreshUploadingState()
    if (photo.fileId) void cancelImage(photo.fileId).catch(() => undefined)
  },

  previewPhotos(event: WxTapEvent): void {
    const index = Number(event.currentTarget.dataset.index)
    const current = this.data.photos[index]
    const urls = this.data.photos.map((item) => item.previewPath)
    if (!current || !urls.length) return
    wx.previewImage({ urls, current: current.previewPath })
  },

  /** 只读取页面内存中的草稿；任何字段变化都通过 setData 更新，不写本地存储。 */
  buildDraft(): SubmissionDraft {
    return {
      name: this.data.name,
      restaurantType: TYPE_VALUES[this.data.typeIndex] ?? 'REGULAR',
      categoryCode: this.data.categoryCodes[this.data.categoryIndex] ?? '',
      priceKind: PRICE_VALUES[this.data.priceIndex] ?? 'UNKNOWN',
      priceText: this.data.priceText,
      address: this.data.address,
      latitude: this.data.latitude,
      longitude: this.data.longitude,
      locationConfirmed: this.data.locationConfirmed,
      businessStatus: BUSINESS_VALUES[this.data.businessIndex] ?? 'OPERATING',
      supportsDineIn: TRI_VALUES[this.data.dineIndex] ?? null,
      supportsDelivery: TRI_VALUES[this.data.deliveryIndex] ?? null,
      tagsText: this.data.tagsText,
      photoIds: this.data.photos.map((item) => item.fileId),
    }
  },

  async submit(): Promise<void> {
    if (this.data.submitting) return
    if (!this.data.loggedIn) {
      this.setData({ errorMessage: `请先登录后再${this.actionName()}。` })
      return
    }
    if (this.data.isCorrection) {
      // 资料未读取成功时提交会漏掉未展示字段，必须等完整快照回填后再允许提交。
      if (this.data.sourceLoading) {
        this.setData({ errorMessage: '正在读取申请资料，请稍候。' })
        return
      }
      if (this.data.sourceError) {
        this.setData({ errorMessage: '申请资料未读取成功，请重新读取后再提交。' })
        return
      }
    }
    if (this.data.uploading || this.data.photos.some((item) => item.status !== 'ready')) {
      this.setData({ errorMessage: '照片尚未全部上传成功，请重试或移除失败照片。' })
      return
    }
    if (this.data.photos.some((item) => !isLongId(item.fileId))) {
      this.setData({ errorMessage: '照片编号异常，请移除后重新上传。' })
      return
    }

    const draft = this.buildDraft()
    const error = validateSubmissionDraft(draft)
    if (error) {
      this.setData({ errorMessage: error })
      return
    }

    const payload = buildSubmissionPayload(draft)
    // 指纹包含模式与目标：同一表单修改不同申请时不能复用旧请求号。
    const content = JSON.stringify([
      this.requestMode || (this.data.isCorrection ? `correction:${this.restaurantId}` : 'new'),
      payload,
    ])
    // 内容未变的重试沿用原请求号；改动后换新编号，避免服务端判定内容冲突。
    if (this.submittedContent && this.submittedContent !== content) {
      this.requestId = createRequestId()
    }
    this.submittedContent = content

    this.setData({ submitting: true, errorMessage: '' })
    try {
      if (this.requestMode === 'edit') {
        // PUT 只提交业务字段，clientRequestId 属于新建申请入口，不能混入修改请求。
        await updateContribution(this.applicationId, payload)
        this.submittedContent = ''
        wx.showToast({ title: '修改已保存', icon: 'success' })
        setTimeout(() => wx.navigateBack(), 600)
        return
      }
      if (this.requestMode === 'resubmit') {
        if (!this.requestId) this.requestId = createRequestId()
        await resubmitContribution(this.applicationId, {
          ...payload,
          clientRequestId: this.requestId,
        })
        this.submittedContent = ''
        this.requestId = ''
        wx.showToast({ title: '已重新提交', icon: 'success' })
        setTimeout(() => wx.navigateBack(), 600)
        return
      }

      if (!this.requestId) this.requestId = createRequestId()
      const input = { ...payload, clientRequestId: this.requestId }
      if (this.data.isCorrection) {
        await submitRestaurantCorrection(this.restaurantId, input)
      } else {
        await submitNewRestaurant(input)
      }
      this.submittedContent = ''
      this.requestId = ''
      wx.showToast({ title: this.data.isCorrection ? '纠错已提交' : '投稿已提交', icon: 'success' })
      // 只代表申请已受理；引导到投稿记录查看审核进度，不直接打开未发布的店铺。
      setTimeout(() => {
        wx.redirectTo({ url: '/pages/account/contributions/index' })
      }, 600)
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) {
        this.setData({ loggedIn: false, errorMessage: '登录已失效，请重新登录后重试。' })
      } else {
        this.setData({ errorMessage: toErrorMessage(reason) })
      }
      this.setData({ submitting: false })
    }
  },

  /** 提交按钮与错误文案共用的动作名称，避免重提场景仍显示“投稿”。 */
  actionName(): string {
    if (this.requestMode === 'edit') return '保存修改'
    if (this.requestMode === 'resubmit') return '重新提交'
    return this.data.isCorrection ? '提交纠错' : '提交投稿'
  },

  refreshUploadingState(): void {
    this.setData({ uploading: this.data.photos.some((item) => item.status === 'uploading') })
  },

  patchPhoto(key: number, patch: Partial<DraftPhoto>): void {
    this.setData({
      photos: this.data.photos.map((item) => (item.key === key ? { ...item, ...patch } : item)),
    })
  },

  login(): void {
    // 登录后回到同一目标继续填写，不能退回新店投稿或丢失纠错/申请编号。
    let target = '/pages/submission/index'
    if (this.requestMode) {
      target +=
        `?mode=${this.requestMode}` +
        `&requestId=${encodeURIComponent(this.applicationId)}`
    } else if (this.data.isCorrection) {
      target += `?id=${encodeURIComponent(this.restaurantId)}`
    }
    wx.navigateTo({
      url: `/pages/login/index?redirect=${encodeURIComponent(target)}`,
    })
  },
})
