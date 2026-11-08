import { httpClient } from '@/shared/api/httpClient'
import type { ApiResponse } from '@/shared/api/api.types'
export interface AnnouncementDetail {
  id: string
  title: string
  content: string
  status: 'PUBLISHED' | 'WITHDRAWN'
  publishedBy: string
  publishedAt: string
  withdrawnBy: string | null
  withdrawnAt: string | null
  replayed: boolean
}
export type AnnouncementStatus = 'ALL' | 'PUBLISHED' | 'WITHDRAWN'
export interface AnnouncementPage {
  records: AnnouncementDetail[]
  total: number
  page: number
  pageSize: number
  totalPages: number
}
export interface AnnouncementInput {
  clientRequestId: string
  title: string
  content: string
}
export const announcementId = (id: unknown): id is string =>
  typeof id === 'string' && /^[1-9]\d{0,18}$/.test(id) && BigInt(id) <= 9223372036854775807n
export const normalizeAnnouncementText = (s: string) => s.replace(/\r\n?/g, '\n').trim()
const date = (s: unknown): s is string => typeof s === 'string' && Number.isFinite(Date.parse(s))
const count = (n: unknown): n is number => Number.isSafeInteger(n) && (n as number) >= 0
const invalid = () => new Error('公告资料无法确认，请重新加载。')
/** 只信任白名单字段和完整生命周期，正文始终作为纯文本交给视图。 */
function detail(row: AnnouncementDetail): AnnouncementDetail {
  if (
    !row ||
    !announcementId(row.id) ||
    !announcementId(row.publishedBy) ||
    !date(row.publishedAt) ||
    typeof row.title !== 'string' ||
    !row.title.trim() ||
    Array.from(row.title).length > 120 ||
    typeof row.content !== 'string' ||
    !row.content.trim() ||
    Array.from(row.content).length > 10000 ||
    typeof row.replayed !== 'boolean'
  )
    throw invalid()
  if (
    row.status === 'PUBLISHED'
      ? row.withdrawnBy !== null || row.withdrawnAt !== null
      : row.status !== 'WITHDRAWN' ||
        !announcementId(row.withdrawnBy) ||
        !date(row.withdrawnAt) ||
        Date.parse(row.withdrawnAt) < Date.parse(row.publishedAt)
  )
    throw invalid()
  return {
    id: row.id,
    title: row.title,
    content: row.content,
    status: row.status,
    publishedBy: row.publishedBy,
    publishedAt: row.publishedAt,
    withdrawnBy: row.withdrawnBy,
    withdrawnAt: row.withdrawnAt,
    replayed: row.replayed,
  }
}
const options = (token: string, signal: AbortSignal) => ({
  signal,
  headers: { Authorization: `Bearer ${token}` },
})
const admin = '/api/admin/announcements'
/** 列表绑定发起时凭证；失败不能伪装为空列表。 */
export async function fetchAdminAnnouncements(
  status: AnnouncementStatus,
  page: number,
  token: string,
  signal: AbortSignal,
): Promise<AnnouncementPage> {
  const response = await httpClient.get<ApiResponse<AnnouncementPage>>(admin, {
    ...options(token, signal),
    params: { status, page, pageSize: 20 },
  })
  const value = response.data.data
  if (
    !response.data.success ||
    !value ||
    value.page !== page ||
    value.pageSize !== 20 ||
    !count(value.total) ||
    !count(value.totalPages) ||
    value.totalPages !== Math.ceil(value.total / 20) ||
    !Array.isArray(value.records) ||
    value.records.length > 20 ||
    value.records.length > value.total
  )
    throw invalid()
  const records = value.records.map(detail)
  if (
    new Set(records.map((r) => r.id)).size !== records.length ||
    records.some((r) => status !== 'ALL' && r.status !== status)
  )
    throw invalid()
  return { records, total: value.total, totalPages: value.totalPages, page, pageSize: 20 }
}
/** 一次发布尝试的UUID及正文必须保持一致；未知结果由调用方用原请求重试。 */
export async function publishAnnouncement(
  input: AnnouncementInput,
  token: string,
  signal: AbortSignal,
) {
  const response = await httpClient.post<ApiResponse<AnnouncementDetail>>(
    admin,
    input,
    options(token, signal),
  )
  if (!response.data.success) throw invalid()
  const result = detail(response.data.data)
  if (
    result.title !== normalizeAnnouncementText(input.title) ||
    result.content !== normalizeAnnouncementText(input.content)
  )
    throw invalid()
  return result
}
export async function withdrawAnnouncement(id: string, token: string, signal: AbortSignal) {
  if (!announcementId(id)) throw invalid()
  const response = await httpClient.post<ApiResponse<AnnouncementDetail>>(
    `${admin}/${id}/withdraw`,
    null,
    options(token, signal),
  )
  if (!response.data.success) throw invalid()
  const result = detail(response.data.data)
  if (result.id !== id || result.status !== 'WITHDRAWN') throw invalid()
  return result
}
/** 用户详情与管理员历史分开固定路径，用户请求不使用管理接口绕过撤回/删除。 */
async function fetchDetail(id: string, token: string, signal: AbortSignal, management: boolean) {
  if (!announcementId(id)) throw invalid()
  const response = await httpClient.get<ApiResponse<AnnouncementDetail>>(
    `${management ? admin : '/api/me/announcements'}/${id}`,
    options(token, signal),
  )
  if (!response.data.success) throw invalid()
  const result = detail(response.data.data)
  if (result.id !== id || (!management && result.status !== 'PUBLISHED')) throw invalid()
  return result
}
export const fetchAnnouncement = (id: string, token: string, signal: AbortSignal) =>
  fetchDetail(id, token, signal, false)
export const fetchAdminAnnouncement = (id: string, token: string, signal: AbortSignal) =>
  fetchDetail(id, token, signal, true)
