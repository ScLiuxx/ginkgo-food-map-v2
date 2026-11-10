import { absoluteUrl } from '../../config/env'
import { request } from '../../shared/api/request'
import { getToken } from '../../shared/auth/session'
import { formatDate } from '../../shared/lib/format'

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
    restaurantId: application.restaurantId ?? '',
    photoPositions: raw.photoPositions,
    photoText: raw.photoPositions.length > 0 ? `${raw.photoPositions.length} 张附图` : '',
  }
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
