import { absoluteUrl } from '../../config/env'
import { request } from '../../shared/api/request'
import { getToken } from '../../shared/auth/session'
import { formatDate } from '../../shared/lib/format'
import { isLongId } from '../../shared/lib/id'
import type {
  SubmissionBusinessStatus,
  SubmissionDraft,
  SubmissionInput,
  SubmissionPayload,
  SubmissionPriceKind,
  RestaurantType,
  TriState,
} from './submission'

export type ContributionStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN'
export type ContributionFilter = 'ALL' | ContributionStatus
export type ContributionType = 'CREATE' | 'UPDATE'

export interface ContributionRecordRaw {
  id: string
  restaurantId: string | null
  previousRequestId: string | null
  requestType: ContributionType
  status: ContributionStatus
  name: string
  createdAt: string
  reviewedAt: string | null
  withdrawnAt: string | null
  photoCount: number
}

export interface ContributionListRaw {
  userId: string
  records: ContributionRecordRaw[]
  counts: Record<ContributionFilter, number>
  total: number
  totalPages: number
  page: number
  pageSize: number
}

export interface ContributionCard {
  id: string
  typeLabel: string
  status: ContributionStatus
  statusLabel: string
  statusClass: string
  name: string
  dateText: string
  photoText: string
  noteText: string
}

export interface ContributionListView {
  records: ContributionCard[]
  counts: Record<ContributionFilter, number>
  total: number
  page: number
  totalPages: number
}

interface ContributionSnapshotRaw {
  name?: string | null
  categoryCode?: string | null
  categoryName?: string | null
  restaurantType?: 'REGULAR' | 'CANTEEN' | null
  priceKind?: 'UNKNOWN' | 'EXACT' | 'ESTIMATE' | null
  pricePerPerson?: number | null
  address?: string | null
  latitude?: number | null
  longitude?: number | null
  locationConfirmed?: boolean | null
  businessStatus?: 'OPERATING' | 'CLOSED' | null
  supportsDineIn?: boolean | null
  supportsDelivery?: boolean | null
  tags?: string[] | null
}

export interface ContributionDetailRaw {
  userId: string
  application: ContributionRecordRaw
  proposed: ContributionSnapshotRaw
  approved: ContributionSnapshotRaw | null
  rejectReason: string | null
  targetAvailable: boolean
  photoPositions: number[]
}

export interface ContributionFieldRow {
  label: string
  value: string
}

export interface ContributionDetailView {
  id: string
  /** 申请类型决定驳回重提的接口语义；CREATE 不依赖现存的公开店铺。 */
  requestType: ContributionType
  typeLabel: string
  status: ContributionStatus
  statusLabel: string
  statusClass: string
  name: string
  createdText: string
  reviewedText: string
  withdrawnText: string
  previousRequestText: string
  rejectReason: string
  proposed: ContributionFieldRow[]
  approved: ContributionFieldRow[]
  targetAvailable: boolean
  /** 仅待审申请可整体修改。 */
  canEdit: boolean
  /** 仅被驳回申请可修改后重新提交；UPDATE 还要求原店铺仍可访问。 */
  canResubmit: boolean
  restaurantId: string
  photoPositions: number[]
  photoText: string
}

const CONTRIBUTION_PAGE_SIZE = 10
const statusLabels: Record<ContributionStatus, string> = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  WITHDRAWN: '已撤回',
}
const statusClasses: Record<ContributionStatus, string> = {
  PENDING: 'pending',
  APPROVED: 'approved',
  REJECTED: 'rejected',
  WITHDRAWN: 'withdrawn',
}
const typeLabels: Record<ContributionType, string> = {
  CREATE: '新店投稿',
  UPDATE: '资料纠错',
}
const statusNotes: Record<ContributionStatus, string> = {
  PENDING: '等待管理员审核',
  APPROVED: '审核通过，已更新到店铺资料',
  REJECTED: '审核未通过，可查看驳回原因',
  WITHDRAWN: '已由你撤回，不可恢复',
}

function validId(value: unknown): value is string {
  return typeof value === 'string' && /^[1-9]\d*$/.test(value)
}

function isValidDate(value: unknown): boolean {
  return typeof value === 'string' && Number.isFinite(Date.parse(value))
}

function invalid(): Error {
  return new Error('投稿记录无法确认，请重新加载。')
}

/** 状态和类型必须来自固定枚举，未知值不能当作待审核展示。 */
function isStatus(value: unknown): value is ContributionStatus {
  return value === 'PENDING' || value === 'APPROVED' || value === 'REJECTED' || value === 'WITHDRAWN'
}

function isType(value: unknown): value is ContributionType {
  return value === 'CREATE' || value === 'UPDATE'
}

function toApplication(raw: ContributionRecordRaw): ContributionRecordRaw {
  if (
    !raw ||
    !validId(raw.id) ||
    (raw.restaurantId !== null && !validId(raw.restaurantId)) ||
    (raw.previousRequestId !== null && !validId(raw.previousRequestId)) ||
    !isType(raw.requestType) ||
    !isStatus(raw.status) ||
    typeof raw.name !== 'string' ||
    !raw.name.trim() ||
    !isValidDate(raw.createdAt) ||
    (raw.reviewedAt !== null && !isValidDate(raw.reviewedAt)) ||
    (raw.withdrawnAt !== null && !isValidDate(raw.withdrawnAt)) ||
    !Number.isSafeInteger(raw.photoCount) ||
    raw.photoCount < 0 ||
    raw.photoCount > 5
  ) {
    throw invalid()
  }
  return raw
}

function toCard(raw: ContributionRecordRaw): ContributionCard {
  const application = toApplication(raw)
  return {
    id: application.id,
    typeLabel: typeLabels[application.requestType],
    status: application.status,
    statusLabel: statusLabels[application.status],
    statusClass: statusClasses[application.status],
    name: application.name,
    dateText: formatDate(application.createdAt),
    photoText: application.photoCount > 0 ? `${application.photoCount} 张附图` : '',
    noteText: statusNotes[application.status],
  }
}

/**
 * 读取本人投稿与纠错列表。
 *
 * 状态计数始终来自服务端同一快照；不能通过参数查看其他申请人。
 */
export async function fetchMyContributions(
  status: ContributionFilter,
  page: number,
): Promise<ContributionListView> {
  const raw = await request<ContributionListRaw>({
    path: `/api/me/restaurant-requests?status=${status}&page=${page}&pageSize=${CONTRIBUTION_PAGE_SIZE}`,
    auth: 'required',
  })
  if (
    !raw ||
    !Array.isArray(raw.records) ||
    raw.records.length > CONTRIBUTION_PAGE_SIZE ||
    raw.page !== page ||
    raw.pageSize !== CONTRIBUTION_PAGE_SIZE ||
    !Number.isSafeInteger(raw.total) ||
    raw.total < 0 ||
    !Number.isSafeInteger(raw.totalPages) ||
    raw.totalPages !== Math.ceil(raw.total / CONTRIBUTION_PAGE_SIZE) ||
    !raw.counts ||
    !(['ALL', 'PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'] as ContributionFilter[]).every(
      (key) => Number.isSafeInteger(raw.counts[key]) && raw.counts[key] >= 0,
    )
  ) {
    throw invalid()
  }
  const ids = new Set<string>()
  const records = raw.records.map((record) => {
    const card = toCard(record)
    if (ids.has(card.id)) throw invalid()
    ids.add(card.id)
    return card
  })
  return {
    records,
    counts: raw.counts,
    total: raw.total,
    page: raw.page,
    totalPages: raw.totalPages,
  }
}

/** 将快照转成固定字段行；历史缺失字段显示“未填写”，不猜测成 0 或营业。 */
function toSnapshotRows(snapshot: ContributionSnapshotRaw): ContributionFieldRow[] {
  const price = snapshot.pricePerPerson
  const supports: string[] = []
  if (snapshot.supportsDineIn === true) supports.push('堂食')
  if (snapshot.supportsDelivery === true) supports.push('外卖')
  const supportsText = supports.length > 0 ? supports.join(' · ') : '未知'

  return [
    { label: '店名', value: snapshot.name?.trim() || '未填写' },
    {
      label: '分类',
      value: snapshot.categoryName?.trim() || snapshot.categoryCode?.trim() || '未填写',
    },
    {
      label: '类型',
      value:
        snapshot.restaurantType === 'CANTEEN'
          ? '食堂'
          : snapshot.restaurantType === 'REGULAR'
            ? '餐厅'
            : '未填写',
    },
    {
      label: '参考人均',
      value:
        typeof price === 'number' && Number.isFinite(price) && price > 0
          ? `${snapshot.priceKind === 'ESTIMATE' ? '约 ' : ''}¥${price}`
          : '未填写',
    },
    { label: '地址', value: snapshot.address?.trim() || '未填写' },
    {
      label: '位置',
      value:
        snapshot.locationConfirmed === true &&
        typeof snapshot.latitude === 'number' &&
        typeof snapshot.longitude === 'number' &&
        Number.isFinite(snapshot.latitude) &&
        Number.isFinite(snapshot.longitude)
          ? `${snapshot.latitude.toFixed(6)}, ${snapshot.longitude.toFixed(6)}`
          : '未确认',
    },
    {
      label: '营业状态',
      value:
        snapshot.businessStatus === 'OPERATING'
          ? '营业中'
          : snapshot.businessStatus === 'CLOSED'
            ? '已停业'
            : '未填写',
    },
    { label: '用餐方式', value: supportsText },
    {
      label: '标签',
      value:
        Array.isArray(snapshot.tags) && snapshot.tags.length > 0
          ? snapshot.tags.filter((tag) => typeof tag === 'string' && tag.trim()).join('、') || '无'
          : '无',
    },
  ]
}

/** 读取申请详情；只读查看，不提供修改、撤回或重提入口。 */
export async function fetchContributionDetail(id: string): Promise<ContributionDetailView> {
  const raw = await request<ContributionDetailRaw>({
    path: `/api/me/restaurant-requests/${encodeURIComponent(id)}`,
    auth: 'required',
  })
  if (
    !raw ||
    !raw.application ||
    !raw.proposed ||
    typeof raw.proposed !== 'object' ||
    (raw.approved !== null && typeof raw.approved !== 'object') ||
    (raw.rejectReason !== null && typeof raw.rejectReason !== 'string') ||
    typeof raw.targetAvailable !== 'boolean' ||
    !Array.isArray(raw.photoPositions) ||
    raw.photoPositions.length > 5 ||
    !raw.photoPositions.every(
      (position, index) =>
        Number.isSafeInteger(position) &&
        position >= 0 &&
        position <= 4 &&
        raw.photoPositions.indexOf(position) === index,
    )
  ) {
    throw invalid()
  }
  const application = toApplication(raw.application)
  if (application.id !== id) throw invalid()

  return {
    id: application.id,
    requestType: application.requestType,
    typeLabel: typeLabels[application.requestType],
    status: application.status,
    statusLabel: statusLabels[application.status],
    statusClass: statusClasses[application.status],
    name: application.name,
    createdText: formatDate(application.createdAt),
    reviewedText: application.reviewedAt ? formatDate(application.reviewedAt) : '',
    withdrawnText: application.withdrawnAt ? formatDate(application.withdrawnAt) : '',
    previousRequestText: application.previousRequestId
      ? `由申请 #${application.previousRequestId} 重新提交`
      : '',
    rejectReason: raw.rejectReason?.trim() ?? '',
    proposed: toSnapshotRows(raw.proposed),
    approved: raw.approved ? toSnapshotRows(raw.approved) : [],
    targetAvailable: raw.targetAvailable,
    // 新店投稿没有公开目标；UPDATE 的原店铺被删除后服务端也拒绝重提。
    canEdit: application.status === 'PENDING',
    canResubmit:
      application.status === 'REJECTED' &&
      (application.requestType === 'CREATE' || raw.targetAvailable),
    restaurantId: application.restaurantId ?? '',
    photoPositions: raw.photoPositions,
    photoText: raw.photoPositions.length > 0 ? `${raw.photoPositions.length} 张附图` : '',
  }
}

/**
 * 待审修改与被驳回重提共用的表单数据。
 *
 * `draft.photoIds` 始终为空：详情接口只返回附图位置，不暴露文件编号，
 * 因此修改与重提都必须重新选择并上传图片，不能假装保留原附图。
 */
export interface ContributionEditSource {
  id: string
  requestType: ContributionType
  status: ContributionStatus
  restaurantId: string
  targetAvailable: boolean
  draft: SubmissionDraft
}

/** PUT 修改不接收 clientRequestId；由服务端以申请编号和完整内容确定幂等边界。 */
export type ContributionPayload = SubmissionPayload

function finiteCoordinate(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

/** 把申请快照还原成可编辑草稿；缺失字段保持空值，不用默认值补造资料。 */
function toEditDraft(snapshot: ContributionSnapshotRaw): SubmissionDraft {
  const restaurantType: RestaurantType =
    snapshot.restaurantType === 'CANTEEN' ? 'CANTEEN' : 'REGULAR'
  const priceKind: SubmissionPriceKind =
    snapshot.priceKind === 'EXACT' || snapshot.priceKind === 'ESTIMATE'
      ? snapshot.priceKind
      : 'UNKNOWN'
  const hasPrice = priceKind !== 'UNKNOWN' && finiteCoordinate(snapshot.pricePerPerson)
  const latitude = finiteCoordinate(snapshot.latitude) ? snapshot.latitude : null
  const longitude = finiteCoordinate(snapshot.longitude) ? snapshot.longitude : null
  // 坐标文本只在“原申请已确认且经纬度完整”时回填，否则要求用户重新选择。
  let latitudeText = ''
  let longitudeText = ''
  if (snapshot.locationConfirmed === true && latitude !== null && longitude !== null) {
    latitudeText = latitude.toFixed(6)
    longitudeText = longitude.toFixed(6)
  }
  const hasLocation = latitudeText !== '' && longitudeText !== ''

  return {
    name: snapshot.name?.trim() ?? '',
    restaurantType,
    // 食堂没有分类；普通餐厅沿用申请中的分类编码，是否启用仍由服务端判定。
    categoryCode: restaurantType === 'CANTEEN' ? '' : (snapshot.categoryCode?.trim() ?? ''),
    priceKind,
    priceText: hasPrice ? String(snapshot.pricePerPerson) : '',
    address: snapshot.address?.trim() ?? '',
    latitude: latitudeText,
    longitude: longitudeText,
    locationConfirmed: hasLocation,
    businessStatus: snapshot.businessStatus === 'CLOSED' ? 'CLOSED' : 'OPERATING',
    supportsDineIn:
      snapshot.supportsDineIn === true ? true : snapshot.supportsDineIn === false ? false : null,
    supportsDelivery:
      snapshot.supportsDelivery === true
        ? true
        : snapshot.supportsDelivery === false
          ? false
          : null,
    tagsText: Array.isArray(snapshot.tags)
      ? snapshot.tags.filter((tag) => typeof tag === 'string' && tag.trim()).join('\n')
      : '',
    photoIds: [],
  }
}

/**
 * 读取修改/重提所需的申请快照。
 *
 * 只有待审申请可修改、被驳回申请可重提；状态不符时直接拒绝，
 * 避免用户在过期页面提交后得到服务端 409 才得知状态已变化。
 */
export async function fetchContributionEditSource(
  id: string,
  mode: 'edit' | 'resubmit',
): Promise<ContributionEditSource> {
  if (!isLongId(id)) throw new Error('申请编号无效，请返回列表后重试。')
  const raw = await request<ContributionDetailRaw>({
    path: `/api/me/restaurant-requests/${encodeURIComponent(id)}`,
    auth: 'required',
  })
  if (
    !raw ||
    !raw.application ||
    !raw.proposed ||
    typeof raw.proposed !== 'object' ||
    (raw.rejectReason !== null && typeof raw.rejectReason !== 'string') ||
    typeof raw.targetAvailable !== 'boolean'
  ) {
    throw invalid()
  }
  const application = toApplication(raw.application)
  if (application.id !== id) throw invalid()
  if (mode === 'edit' && application.status !== 'PENDING') {
    throw new Error('只有待审核申请可以修改，请刷新后查看最新状态。')
  }
  if (mode === 'resubmit') {
    if (application.status !== 'REJECTED') {
      throw new Error('只有被驳回的申请可以重新提交。')
    }
    if (application.requestType === 'UPDATE' && !raw.targetAvailable) {
      throw new Error('原店铺已不可用，无法重新提交资料纠错。')
    }
  }

  return {
    id: application.id,
    requestType: application.requestType,
    status: application.status,
    restaurantId: application.restaurantId ?? '',
    targetAvailable: raw.targetAvailable,
    draft: toEditDraft(raw.proposed),
  }
}

/** 整体替换本人待审申请；成功只代表修改已保存，仍需等待管理员重新审核。 */
export async function updateContribution(
  id: string,
  payload: ContributionPayload,
): Promise<{ id: string; status: 'PENDING' }> {
  if (!isLongId(id)) throw new Error('申请编号无法确认，请返回重试。')
  const raw = await request<{ id: string; status: string }>({
    path: `/api/me/restaurant-requests/${encodeURIComponent(id)}`,
    method: 'PUT',
    auth: 'required',
    data: payload,
  })
  if (!raw || raw.id !== id || raw.status !== 'PENDING') {
    throw new Error('修改结果无法确认，请返回投稿记录核对。')
  }
  return { id: raw.id, status: 'PENDING' }
}

/** 撤回本人待审申请；重复撤回同样成功，不改变审核结果或公开资料。 */
export async function withdrawContribution(id: string): Promise<void> {
  if (!isLongId(id)) throw new Error('申请编号无法确认，请返回重试。')
  const raw = await request<{ id: string; status: string }>({
    path: `/api/me/restaurant-requests/${encodeURIComponent(id)}/withdraw`,
    method: 'POST',
    auth: 'required',
  })
  if (!raw || raw.id !== id || raw.status !== 'WITHDRAWN') {
    throw new Error('撤回结果无法确认，请刷新后核对状态。')
  }
}

/**
 * 基于被驳回申请创建新申请。
 *
 * 新申请保留原驳回记录与原因，服务端返回新的申请编号；
 * 图片必须重新上传，不能沿用原申请的文件编号。
 */
export async function resubmitContribution(
  id: string,
  input: SubmissionInput,
): Promise<{ id: string; status: 'PENDING' }> {
  if (!isLongId(id)) throw new Error('原申请编号无法确认，请返回重试。')
  const raw = await request<{ id: string; status: string }>({
    path: `/api/me/restaurant-requests/${encodeURIComponent(id)}/resubmit`,
    method: 'POST',
    auth: 'required',
    data: input,
  })
  if (!raw || !isLongId(raw.id) || raw.status !== 'PENDING') {
    throw new Error('重新提交结果无法确认，请到“我的投稿”核对待审记录。')
  }
  return { id: raw.id, status: 'PENDING' }
}

/**
 * 下载本人申请附图到临时文件。
 *
 * <image> 不支持自定义请求头，因此先用带 Bearer 的 downloadFile 取回临时路径；
 * 服务端每次读取都会重新校验申请归属与图片绑定状态。
 */
export function downloadContributionPhoto(id: string, position: number): Promise<string> {
  return new Promise((resolve, reject) => {
    const token = getToken()
    if (!token) {
      reject(new Error('登录状态已失效，请重新登录。'))
      return
    }
    wx.downloadFile({
      url: absoluteUrl(
        `/api/me/restaurant-requests/${encodeURIComponent(id)}/photos/${position}`,
      ),
      header: { Authorization: `Bearer ${token}` },
      success: (result) => {
        if (result.statusCode === 200 && result.tempFilePath) {
          resolve(result.tempFilePath)
          return
        }
        reject(
          new Error(
            result.statusCode === 401
              ? '登录状态已失效，请重新登录。'
              : '图片读取失败，请重试。',
          ),
        )
      },
      fail: () => reject(new Error('图片读取失败，请检查网络后重试。')),
    })
  })
}
