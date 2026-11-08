import type { ApiResponse } from '@/shared/api/api.types'
import { httpClient } from '@/shared/api/httpClient'

export type NotificationCategory = 'ALL' | 'SYSTEM' | 'COMMENTS' | 'LIKES' | 'FOLLOWERS'
export interface NotificationItem {
  announcementId?: string | null
  announcementTitle?: string | null
  id: string
  requestId: string | null
  category: 'SYSTEM' | 'COMMENTS' | 'LIKES' | 'FOLLOWERS'
  notificationType?: string
  sourceId: string
  reviewId: string | null
  actorUserId: string | null
  actorUsername: string | null
  requestType: 'CREATE' | 'UPDATE' | null
  decision: 'APPROVED' | 'REJECTED' | null
  restaurantName: string | null
  rejectReason: string | null
  createdAt: string
  readAt: string | null
}
export interface NotificationPage {
  userId: string
  category: NotificationCategory
  records: NotificationItem[]
  total: number
  unreadCount: number
  unreadCounts: Record<'SYSTEM' | 'COMMENTS', number> & { LIKES?: number; FOLLOWERS?: number }
  latestSystem: NotificationItem | null
  totalPages: number
  page: number
  pageSize: number
}
const base = '/api/me/notifications'
export const notificationId = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^[1-9]\d{0,18}$/.test(value) &&
  BigInt(value) <= 9223372036854775807n
const count = (value: unknown): value is number =>
  Number.isSafeInteger(value) && (value as number) >= 0
const date = (value: unknown): value is string =>
  typeof value === 'string' && Number.isFinite(Date.parse(value))
const invalid = () => new Error('消息资料无法确认，请重新加载。')

/** 按消息类型显式投影；回复不允许混入审核快照或正文，不执行响应中的任意链接。 */
function item(row: NotificationItem): NotificationItem {
  const announcement = row?.notificationType === 'ANNOUNCEMENT'
  if (
    !row ||
    !(announcement
      ? notificationId(row.announcementId) && row.id === `announcement:${row.announcementId}`
      : notificationId(row.id)) ||
    !notificationId(row.sourceId) ||
    !date(row.createdAt) ||
    (row.readAt !== null &&
      (!date(row.readAt) || Date.parse(row.readAt) < Date.parse(row.createdAt)))
  )
    throw invalid()
  if (announcement) {
    if (
      row.category !== 'SYSTEM' ||
      row.sourceId !== row.announcementId ||
      typeof row.announcementTitle !== 'string' ||
      !row.announcementTitle.trim() ||
      Array.from(row.announcementTitle).length > 120 ||
      [
        row.requestId,
        row.reviewId,
        row.actorUserId,
        row.actorUsername,
        row.requestType,
        row.decision,
        row.restaurantName,
        row.rejectReason,
      ].some((value) => value !== null)
    )
      throw invalid()
  } else if (row.category === 'SYSTEM') {
    if (
      !notificationId(row.requestId) ||
      row.sourceId !== row.requestId ||
      !['CREATE', 'UPDATE'].includes(row.requestType ?? '') ||
      !['APPROVED', 'REJECTED'].includes(row.decision ?? '') ||
      typeof row.restaurantName !== 'string' ||
      !row.restaurantName.trim() ||
      row.reviewId !== null ||
      row.actorUserId !== null ||
      row.actorUsername !== null ||
      (row.decision === 'APPROVED'
        ? row.rejectReason !== null
        : typeof row.rejectReason !== 'string' || !row.rejectReason.trim())
    )
      throw invalid()
  } else if (row.category === 'COMMENTS' || row.category === 'LIKES') {
    if (
      !notificationId(row.reviewId) ||
      !notificationId(row.actorUserId) ||
      typeof row.actorUsername !== 'string' ||
      !row.actorUsername.trim() ||
      row.requestId !== null ||
      row.requestType !== null ||
      row.decision !== null ||
      row.restaurantName !== null ||
      row.rejectReason !== null
    )
      throw invalid()
    if (
      row.category === 'LIKES' &&
      !['REVIEW_LIKED', 'REPLY_LIKED'].includes(row.notificationType ?? '')
    )
      throw invalid()
  } else if (row.category === 'FOLLOWERS') {
    if (
      row.notificationType !== 'USER_FOLLOWED' ||
      !notificationId(row.actorUserId) ||
      typeof row.actorUsername !== 'string' ||
      !row.actorUsername.trim() ||
      row.reviewId !== null ||
      row.requestId !== null ||
      row.requestType !== null ||
      row.decision !== null ||
      row.restaurantName !== null ||
      row.rejectReason !== null
    )
      throw invalid()
  } else throw invalid()
  return {
    ...(announcement
      ? { announcementId: row.announcementId, announcementTitle: row.announcementTitle }
      : {}),
    ...(row.notificationType ? { notificationType: row.notificationType } : {}),
    id: row.id,
    sourceId: row.sourceId,
    category: row.category,
    requestId: row.requestId,
    reviewId: row.reviewId,
    actorUserId: row.actorUserId,
    actorUsername: row.actorUsername,
    requestType: row.requestType,
    decision: row.decision,
    restaurantName: row.restaurantName,
    rejectReason: row.rejectReason,
    createdAt: row.createdAt,
    readAt: row.readAt,
  }
}

/** 分类统计、系统最新摘要和列表属于同一服务端快照，首页不再从混合消息首条猜系统摘要。 */
export async function fetchNotifications(
  category: NotificationCategory,
  page: number,
  userId: string,
  token: string,
  signal: AbortSignal,
): Promise<NotificationPage> {
  const response = await httpClient.get<ApiResponse<NotificationPage>>(base, {
    params: { category, page, pageSize: 10 },
    signal,
    headers: { Authorization: `Bearer ${token}` },
  })
  const value = response.data.data
  if (
    !response.data.success ||
    !value ||
    value.userId !== userId ||
    value.category !== category ||
    value.page !== page ||
    value.pageSize !== 10 ||
    !count(value.total) ||
    !count(value.unreadCount) ||
    value.unreadCount > value.total ||
    !count(value.totalPages) ||
    value.totalPages !== Math.ceil(value.total / 10) ||
    !value.unreadCounts ||
    !count(value.unreadCounts.SYSTEM) ||
    !count(value.unreadCounts.COMMENTS) ||
    (value.unreadCounts.LIKES !== undefined && !count(value.unreadCounts.LIKES)) ||
    (value.unreadCounts.FOLLOWERS !== undefined && !count(value.unreadCounts.FOLLOWERS)) ||
    value.unreadCount !==
      (category === 'ALL'
        ? value.unreadCounts.SYSTEM +
          value.unreadCounts.COMMENTS +
          (value.unreadCounts.LIKES ?? 0) +
          (value.unreadCounts.FOLLOWERS ?? 0)
        : (value.unreadCounts[category] ?? 0)) ||
    !Array.isArray(value.records) ||
    value.records.length > 10 ||
    value.records.length > value.total
  )
    throw invalid()
  const records = value.records.map(item),
    latestSystem = value.latestSystem === null ? null : item(value.latestSystem)
  if (
    new Set(records.map((row) => row.id)).size !== records.length ||
    records.some((row) => category !== 'ALL' && row.category !== category) ||
    (latestSystem && latestSystem.category !== 'SYSTEM')
  )
    throw invalid()
  return {
    userId,
    category,
    records,
    total: value.total,
    unreadCount: value.unreadCount,
    unreadCounts: {
      SYSTEM: value.unreadCounts.SYSTEM,
      COMMENTS: value.unreadCounts.COMMENTS,
      ...(value.unreadCounts.LIKES !== undefined ? { LIKES: value.unreadCounts.LIKES } : {}),
      ...(value.unreadCounts.FOLLOWERS !== undefined
        ? { FOLLOWERS: value.unreadCounts.FOLLOWERS }
        : {}),
    },
    latestSystem,
    totalPages: value.totalPages,
    page,
    pageSize: 10,
  }
}

/** 已读写入不自动重试；先确认服务端成功再跳转或定位，失败保留原通知。 */
export async function markNotificationRead(id: string, token: string, signal: AbortSignal) {
  if (!((id.startsWith('announcement:') && notificationId(id.slice(13))) || notificationId(id)))
    throw invalid()
  await httpClient.post(`${base}/${id}/read`, null, {
    signal,
    headers: { Authorization: `Bearer ${token}` },
  })
}
export async function markAllNotificationsRead(
  category: NotificationCategory,
  token: string,
  signal: AbortSignal,
) {
  await httpClient.post(`${base}/read-all`, null, {
    params: { category },
    signal,
    headers: { Authorization: `Bearer ${token}` },
  })
}

/** 固定调用方的身份凭证；只认204回执，未知结果可由原收件人安全重试。 */
export async function deleteNotification(id: string, token: string, signal: AbortSignal) {
  if (!((id.startsWith('announcement:') && notificationId(id.slice(13))) || notificationId(id)))
    throw invalid()
  const response = await httpClient.delete(`${base}/${id}`, {
    signal,
    headers: { Authorization: `Bearer ${token}` },
  })
  if (response.status !== 204) throw new Error('消息删除结果无法确认，请刷新核对或重试。')
}
