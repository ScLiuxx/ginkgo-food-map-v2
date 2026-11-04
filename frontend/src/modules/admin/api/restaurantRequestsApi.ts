import { httpClient } from '@/shared/api/httpClient'
import type { ApiResponse } from '@/shared/api/api.types'

export type RequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED'
/** 发布记录来自后端正式关联；原申请图片仍使用管理员私有读取协议。 */
export interface PublishedRequestPhoto {
  id: string
  position: number
  cover: boolean
  url: string
}
export interface RequestItem {
  id: string
  requestType: 'CREATE' | 'UPDATE'
  status: RequestStatus
  name: string
  restaurantId: string | null
  submittedBy: string
  submitterName: string
  createdAt: string
  photoCount: number
}
export interface RequestPage {
  records: RequestItem[]
  total: number
  totalPages: number
  page: number
  pageSize: number
  counts: Record<RequestStatus, number>
}
export interface RequestDetail {
  application: RequestItem
  proposed: Record<string, unknown>
  current: Record<string, unknown> | null
  targetDeleted: boolean
  photos: { position: number; url: string }[]
  publishedPhotos?: PublishedRequestPhoto[]
  reviewedBy: string | null
  reviewedAt: string | null
  rejectReason: string | null
  approved: Record<string, unknown> | null
  before: Record<string, unknown> | null
}

/** 审核仅允许修改正式维护协议中的业务字段，禁止带入投稿身份、图片ID或展示文案。 */
export interface ReviewInput {
  restaurantType: 'REGULAR' | 'CANTEEN'
  supportsDineIn: boolean | null
  supportsDelivery: boolean | null
  name: string
  /** 食堂通过时必须为空；普通餐厅必须明确选择美食品类。 */
  categoryCode: string | null
  priceKind: 'UNKNOWN' | 'EXACT' | 'ESTIMATE'
  pricePerPerson: number | null
  address: string | null
  latitude: number
  longitude: number
  locationConfirmed: boolean
  businessStatus: 'OPERATING' | 'CLOSED'
  tags: string[]
}
export interface DecisionReceipt {
  publishedPhotos?: PublishedRequestPhoto[]
  id: string
  status: RequestStatus
  restaurantId: string | null
  reviewedBy: string
  reviewedAt: string
  rejectReason: string | null
  replayed: boolean
}
export interface ReviewCategory {
  code: string
  name: string
  enabled: boolean
}

/** 分类复用公开字典接口，管理端自行消费协议，不依赖普通商户页面内部模型。 */
export async function fetchReviewCategories(signal: AbortSignal): Promise<ReviewCategory[]> {
  return (
    await httpClient.get<ApiResponse<ReviewCategory[]>>('/api/restaurant-categories', { signal })
  ).data.data
}

/** 写请求不能因切换详情而假定被撤销；服务端以申请终态保证重试不会重复发布。 */
export async function approveRequest(
  id: string,
  input: ReviewInput,
  token: string,
): Promise<DecisionReceipt> {
  return (
    await httpClient.post<ApiResponse<DecisionReceipt>>(
      `/api/admin/restaurant-requests/${id}/approve`,
      input,
      { headers: { Authorization: `Bearer ${token}` } },
    )
  ).data.data
}
export async function rejectRequest(
  id: string,
  reason: string,
  token: string,
): Promise<DecisionReceipt> {
  return (
    await httpClient.post<ApiResponse<DecisionReceipt>>(
      `/api/admin/restaurant-requests/${id}/reject`,
      { reason },
      { headers: { Authorization: `Bearer ${token}` } },
    )
  ).data.data
}

/** 所有请求绑定发起时的凭证并支持取消，不缓存管理员私有资料。 */
export async function fetchRequestPage(
  params: { status: RequestStatus; keyword: string; page: number; pageSize: number },
  token: string,
  signal: AbortSignal,
): Promise<RequestPage> {
  return (
    await httpClient.get<ApiResponse<RequestPage>>('/api/admin/restaurant-requests', {
      params,
      signal,
      headers: { Authorization: `Bearer ${token}` },
    })
  ).data.data
}
/** 读取当前所选申请的资料快照，不能以列表摘要代替真实详情。 */
export async function fetchRequestDetail(
  id: string,
  token: string,
  signal: AbortSignal,
): Promise<RequestDetail> {
  return (
    await httpClient.get<ApiResponse<RequestDetail>>(`/api/admin/restaurant-requests/${id}`, {
      signal,
      headers: { Authorization: `Bearer ${token}` },
    })
  ).data.data
}
/** 图片通过Bearer请求读取为Blob，不能用无认证的img请求或持久化OSS链接。 */
export async function fetchRequestPhoto(
  id: string,
  position: number,
  token: string,
  signal: AbortSignal,
): Promise<Blob> {
  return (
    await httpClient.get<Blob>(`/api/admin/restaurant-requests/${id}/photos/${position}`, {
      signal,
      responseType: 'blob',
      headers: { Authorization: `Bearer ${token}` },
    })
  ).data
}
