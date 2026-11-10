import { httpClient } from '@/shared/api/httpClient'
import type { ApiResponse } from '@/shared/api/api.types'
import type { ReportReason } from '../model/reportReasons'

export type ReportTargetType = 'REVIEW' | 'REPLY'
export type ContentReportStatus = 'PENDING' | 'RESOLVED' | 'DISMISSED'

/** 提交回执只保留用户可确认的字段；重复举报仍返回原记录。 */
export interface ReportReceipt {
  reportId: string
  targetType: ReportTargetType
  targetId: string
  status: ContentReportStatus
  duplicate: boolean
  createdAt: string
}

/** 管理员明细包含正文快照和举报双方编号，不能落入普通用户视图。 */
export interface ContentReport {
  id: string
  targetType: ReportTargetType
  targetId: string
  reviewId: string
  reporterUserId: string
  targetAuthorUserId: string
  contentSnapshot: string
  reason: ReportReason
  detail: string | null
  status: ContentReportStatus
  handledBy: string | null
  handledAt: string | null
  decisionNote: string | null
  createdAt: string
}

export interface ContentReportPage {
  records: ContentReport[]
  total: number
  totalPages: number
  page: number
  pageSize: number
}

export interface DismissReceipt {
  reportId: string
  status: 'DISMISSED'
  affectedReports: number
  handledAt: string
}

export interface RemoveReceipt {
  reportId: string
  targetType: ReportTargetType
  targetId: string
  status: 'RESOLVED'
  affectedReports: number
  contentDeleted: boolean
  handledAt: string
}

const pageSize = 20
const admin = '/api/admin/content-reports'
const targetTypes = ['REVIEW', 'REPLY'] as const
const statuses = ['PENDING', 'RESOLVED', 'DISMISSED'] as const
const reasons = [
  'SPAM',
  'ABUSE',
  'PORNOGRAPHY',
  'ILLEGAL',
  'PRIVACY',
  'IRRELEVANT',
  'OTHER',
] as const

/** 所有编号按字符串校验BIGINT范围，防止浏览器精度丢失后误操作其他举报。 */
export const contentReportId = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^[1-9]\d{0,18}$/.test(value) &&
  BigInt(value) <= 9223372036854775807n
const date = (value: unknown): value is string =>
  typeof value === 'string' && Number.isFinite(Date.parse(value))
const count = (value: unknown): value is number =>
  Number.isSafeInteger(value) && (value as number) >= 0
const targetType = (value: unknown): value is ReportTargetType =>
  targetTypes.includes(value as ReportTargetType)
const status = (value: unknown): value is ContentReportStatus =>
  statuses.includes(value as ContentReportStatus)
const reason = (value: unknown): value is ReportReason =>
  reasons.includes(value as ReportReason)
/** 服务端会把说明与备注去空白后存为空值或非空文本，这里按同一语义校验。 */
const optionalText = (value: unknown, max: number): value is string | null =>
  value === null ||
  (typeof value === 'string' && value === value.trim() && !!value && Array.from(value).length <= max)
const invalid = () => new Error('举报资料无法确认，请重新加载。')

/** 按白名单字段投影；正文快照始终作为纯文本交给视图。 */
function item(row: ContentReport): ContentReport {
  if (
    !row ||
    !contentReportId(row.id) ||
    !targetType(row.targetType) ||
    !contentReportId(row.targetId) ||
    !contentReportId(row.reviewId) ||
    !contentReportId(row.reporterUserId) ||
    !contentReportId(row.targetAuthorUserId) ||
    typeof row.contentSnapshot !== 'string' ||
    !row.contentSnapshot.trim() ||
    Array.from(row.contentSnapshot).length > 10000 ||
    !reason(row.reason) ||
    !optionalText(row.detail, 200) ||
    !status(row.status) ||
    !date(row.createdAt)
  )
    throw invalid()

  // 点评举报的所属点评就是被举报内容；回复则必须保留父点评编号。
  if (row.targetType === 'REVIEW' && row.reviewId !== row.targetId) throw invalid()

  if (row.status === 'PENDING') {
    if (row.handledBy !== null || row.handledAt !== null || row.decisionNote !== null)
      throw invalid()
  } else if (
    !contentReportId(row.handledBy) ||
    !date(row.handledAt) ||
    Date.parse(row.handledAt) < Date.parse(row.createdAt) ||
    !optionalText(row.decisionNote, 200)
  )
    throw invalid()

  return {
    id: row.id,
    targetType: row.targetType,
    targetId: row.targetId,
    reviewId: row.reviewId,
    reporterUserId: row.reporterUserId,
    targetAuthorUserId: row.targetAuthorUserId,
    contentSnapshot: row.contentSnapshot,
    reason: row.reason,
    detail: row.detail,
    status: row.status,
    handledBy: row.handledBy,
    handledAt: row.handledAt,
    decisionNote: row.decisionNote,
    createdAt: row.createdAt,
  }
}

const options = (token: string, signal: AbortSignal) => ({
  signal,
  headers: { Authorization: `Bearer ${token}` },
})

/**
 * 提交点评或回复举报。
 *
 * <p>原因必须来自固定枚举，说明最多200个码点；服务端仍会独立校验身份、内容归属和可见性，
 * 前端校验只用于减少无效请求。重复举报返回原记录，调用方按成功处理。
 */
export async function submitContentReport(
  type: ReportTargetType,
  id: string,
  input: { reason: ReportReason; detail?: string | null },
  token: string,
  signal: AbortSignal,
): Promise<ReportReceipt> {
  if (!contentReportId(id) || !reason(input.reason)) throw invalid()
  const detail = input.detail?.trim() || null
  if (detail !== null && Array.from(detail).length > 200) throw invalid()
  const response = await httpClient.post<ApiResponse<ReportReceipt>>(
    `${type === 'REVIEW' ? '/api/reviews' : '/api/review-replies'}/${id}/reports`,
    { reason: input.reason, detail },
    options(token, signal),
  )
  const receipt = response.data.data
  if (
    !response.data.success ||
    !receipt ||
    !contentReportId(receipt.reportId) ||
    receipt.targetType !== type ||
    receipt.targetId !== id ||
    !status(receipt.status) ||
    typeof receipt.duplicate !== 'boolean' ||
    !date(receipt.createdAt) ||
    // 首次提交必须处于待处理；重复提交可能返回已经处置过的原记录。
    (!receipt.duplicate && receipt.status !== 'PENDING')
  )
    throw invalid()
  return {
    reportId: receipt.reportId,
    targetType: receipt.targetType,
    targetId: receipt.targetId,
    status: receipt.status,
    duplicate: receipt.duplicate,
    createdAt: receipt.createdAt,
  }
}

/** 管理端分页绑定发起时凭证；空结果仍以真实total为准，不伪装成加载失败。 */
export async function fetchContentReports(
  filter: ContentReportStatus,
  page: number,
  token: string,
  signal: AbortSignal,
): Promise<ContentReportPage> {
  if (!status(filter) || !Number.isSafeInteger(page) || page < 1) throw invalid()
  const response = await httpClient.get<ApiResponse<ContentReportPage>>(admin, {
    params: { status: filter, page, pageSize },
    ...options(token, signal),
  })
  const value = response.data.data
  if (
    !response.data.success ||
    !value ||
    value.page !== page ||
    value.pageSize !== pageSize ||
    !count(value.total) ||
    !count(value.totalPages) ||
    value.totalPages !== Math.ceil(value.total / pageSize) ||
    !Array.isArray(value.records) ||
    value.records.length > pageSize ||
    value.records.length > value.total
  )
    throw invalid()
  const records = value.records.map(item)
  if (
    new Set(records.map((row) => row.id)).size !== records.length ||
    records.some((row) => row.status !== filter)
  )
    throw invalid()
  return { records, total: value.total, totalPages: value.totalPages, page, pageSize }
}

export async function fetchContentReport(
  id: string,
  token: string,
  signal: AbortSignal,
): Promise<ContentReport> {
  if (!contentReportId(id)) throw invalid()
  const response = await httpClient.get<ApiResponse<ContentReport>>(`${admin}/${id}`, {
    ...options(token, signal),
  })
  if (!response.data.success) throw invalid()
  const value = item(response.data.data)
  if (value.id !== id) throw invalid()
  return value
}

/** 驳回会把同一内容下所有待处理举报一并结案，affectedReports用于向管理员说明影响范围。 */
export async function dismissContentReport(
  id: string,
  note: string | null,
  token: string,
  signal: AbortSignal,
): Promise<DismissReceipt> {
  if (!contentReportId(id) || (note !== null && Array.from(note).length > 200)) throw invalid()
  const response = await httpClient.post<ApiResponse<DismissReceipt>>(
    `${admin}/${id}/dismiss`,
    note === null ? null : { note },
    options(token, signal),
  )
  const receipt = response.data.data
  if (
    !response.data.success ||
    !receipt ||
    receipt.reportId !== id ||
    receipt.status !== 'DISMISSED' ||
    !count(receipt.affectedReports) ||
    !date(receipt.handledAt)
  )
    throw invalid()
  return receipt
}

/** 删除内容不可恢复；contentDeleted=false表示作者已先删除，本次只补完举报结案。 */
export async function removeContentReport(
  id: string,
  note: string | null,
  token: string,
  signal: AbortSignal,
): Promise<RemoveReceipt> {
  if (!contentReportId(id) || (note !== null && Array.from(note).length > 200)) throw invalid()
  const response = await httpClient.post<ApiResponse<RemoveReceipt>>(
    `${admin}/${id}/remove`,
    note === null ? null : { note },
    options(token, signal),
  )
  const receipt = response.data.data
  if (
    !response.data.success ||
    !receipt ||
    receipt.reportId !== id ||
    !targetType(receipt.targetType) ||
    !contentReportId(receipt.targetId) ||
    receipt.status !== 'RESOLVED' ||
    !count(receipt.affectedReports) ||
    typeof receipt.contentDeleted !== 'boolean' ||
    !date(receipt.handledAt)
  )
    throw invalid()
  return receipt
}
