import type { ApiResponse } from '@/shared/api/api.types'
import { httpClient } from '@/shared/api/httpClient'

export type MyRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED'
export type MyRequestFilter = 'ALL' | MyRequestStatus
export interface MyRequestItem {
  id: string
  restaurantId: string | null
  requestType: 'CREATE' | 'UPDATE'
  status: MyRequestStatus
  name: string
  createdAt: string
  reviewedAt: string | null
  photoCount: number
}
export interface MyRequestPage {
  userId: string
  records: MyRequestItem[]
  counts: Record<MyRequestFilter, number>
  total: number
  totalPages: number
  page: number
  pageSize: number
}
export interface MyRequestDetail {
  userId: string
  application: MyRequestItem
  proposed: Record<string, unknown>
  approved: Record<string, unknown> | null
  rejectReason: string | null
  targetAvailable: boolean
  photoPositions: number[]
}
export const myRequestStatusNames: Record<MyRequestFilter, string> = {
  ALL: '全部',
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回',
}
const base = '/api/me/restaurant-requests'
const id = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^[1-9]\d{0,18}$/.test(value) &&
  BigInt(value) <= 9223372036854775807n
const count = (value: unknown): value is number =>
  Number.isSafeInteger(value) && (value as number) >= 0
const date = (value: unknown): value is string =>
  typeof value === 'string' && Number.isFinite(Date.parse(value))
const invalid = () => new Error('投稿记录无法确认，请重新加载。')

/** 显式投影列表字段，拒绝未知状态/身份及损坏分页，避免把异常当成真实空记录。 */
function item(value: MyRequestItem): MyRequestItem {
  if (
    !value ||
    !id(value.id) ||
    (value.restaurantId !== null && !id(value.restaurantId)) ||
    !['CREATE', 'UPDATE'].includes(value.requestType) ||
    !['PENDING', 'APPROVED', 'REJECTED'].includes(value.status) ||
    typeof value.name !== 'string' ||
    !date(value.createdAt) ||
    (value.status === 'PENDING' ? value.reviewedAt !== null : !date(value.reviewedAt)) ||
    !count(value.photoCount) ||
    value.photoCount > 5
  )
    throw invalid()
  return {
    id: value.id,
    restaurantId: value.restaurantId,
    requestType: value.requestType,
    status: value.status,
    name: value.name,
    createdAt: value.createdAt,
    reviewedAt: value.reviewedAt,
    photoCount: value.photoCount,
  }
}

/** 只发送分页/状态；userId仅用于回执归属校验，不作为服务端账号选择参数。 */
export async function fetchMyRequestPage(
  status: MyRequestFilter,
  page: number,
  userId: string,
  token: string,
  signal: AbortSignal,
): Promise<MyRequestPage> {
  const response = await httpClient.get<ApiResponse<MyRequestPage>>(base, {
    params: { status, page, pageSize: 10 },
    signal,
    headers: { Authorization: `Bearer ${token}` },
  })
  const value = response.data.data
  if (
    !response.data.success ||
    !value ||
    value.userId !== userId ||
    value.page !== page ||
    value.pageSize !== 10 ||
    !count(value.total) ||
    !count(value.totalPages) ||
    value.totalPages !== Math.ceil(value.total / 10) ||
    !value.counts ||
    !(['ALL', 'PENDING', 'APPROVED', 'REJECTED'] as const).every((key) =>
      count(value.counts[key]),
    ) ||
    value.counts.ALL !== value.counts.PENDING + value.counts.APPROVED + value.counts.REJECTED ||
    value.total !== value.counts[status] ||
    !Array.isArray(value.records) ||
    value.records.length > 10 ||
    value.records.length > value.total
  )
    throw invalid()
  const records = value.records.map(item)
  if (
    new Set(records.map((row) => row.id)).size !== records.length ||
    (status !== 'ALL' && records.some((row) => row.status !== status))
  )
    throw invalid()
  return {
    userId,
    records,
    counts: {
      ALL: value.counts.ALL,
      PENDING: value.counts.PENDING,
      APPROVED: value.counts.APPROVED,
      REJECTED: value.counts.REJECTED,
    },
    total: value.total,
    totalPages: value.totalPages,
    page,
    pageSize: 10,
  }
}
const snapshotFields = [
  'name',
  'categoryCode',
  'categoryName',
  'restaurantType',
  'priceKind',
  'pricePerPerson',
  'address',
  'latitude',
  'longitude',
  'locationConfirmed',
  'businessStatus',
  'supportsDineIn',
  'supportsDelivery',
  'tags',
]
function snapshot(value: Record<string, unknown>): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw invalid()
  return Object.fromEntries(snapshotFields.map((key) => [key, value[key]]))
}

/** 详情只接受匹配本次申请与账号的回执；不消费管理端快照或服务端提供的任意图片URL。 */
export async function fetchMyRequestDetail(
  requestId: string,
  userId: string,
  token: string,
  signal: AbortSignal,
): Promise<MyRequestDetail> {
  if (!id(requestId)) throw invalid()
  const response = await httpClient.get<ApiResponse<MyRequestDetail>>(`${base}/${requestId}`, {
    signal,
    headers: { Authorization: `Bearer ${token}` },
  })
  const value = response.data.data
  if (
    !response.data.success ||
    !value ||
    value.userId !== userId ||
    value.application?.id !== requestId ||
    typeof value.targetAvailable !== 'boolean' ||
    !Array.isArray(value.photoPositions) ||
    value.photoPositions.length > 5 ||
    new Set(value.photoPositions).size !== value.photoPositions.length ||
    value.photoPositions.some(
      (position, index) =>
        !Number.isInteger(position) ||
        position < 0 ||
        position > 4 ||
        (index > 0 && position <= value.photoPositions[index - 1]!),
    )
  )
    throw invalid()
  const application = item(value.application)
  if (
    application.photoCount !== value.photoPositions.length ||
    (application.status === 'APPROVED' ? !value.approved : value.approved !== null) ||
    (application.status === 'REJECTED'
      ? typeof value.rejectReason !== 'string' || !value.rejectReason.trim()
      : value.rejectReason !== null) ||
    (value.targetAvailable && !application.restaurantId)
  )
    throw invalid()
  return {
    userId,
    application,
    proposed: snapshot(value.proposed),
    approved: value.approved === null ? null : snapshot(value.approved),
    rejectReason: value.rejectReason,
    targetAvailable: value.targetAvailable,
    photoPositions: [...value.photoPositions],
  }
}

/** 私有图片每次携带固定会话，拿到字节后由页面创建短期Blob URL，退出立即释放。 */
export async function fetchMyRequestPhoto(
  requestId: string,
  position: number,
  token: string,
  signal: AbortSignal,
): Promise<Blob> {
  if (!id(requestId) || !Number.isInteger(position) || position < 0 || position > 4) throw invalid()
  const response = await httpClient.get<Blob>(`${base}/${requestId}/photos/${position}`, {
    signal,
    headers: { Authorization: `Bearer ${token}` },
    responseType: 'blob',
  })
  if (
    !(response.data instanceof Blob) ||
    response.data.type !== 'image/jpeg' ||
    !response.data.size
  )
    throw new Error('投稿图片无法读取，请重试。')
  return response.data
}
