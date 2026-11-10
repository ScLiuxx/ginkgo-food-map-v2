import { ApiError, request, toErrorMessage } from '../../shared/api/request'
import { getCurrentUser, getToken } from '../../shared/auth/session'
import { formatDate } from '../../shared/lib/format'

/**
 * 消息中心接口与展示模型。
 *
 * 设计要点：
 * - 所有接口都要求登录，收件人只由服务端按当前令牌确定，页面不能传入他人身份；
 * - 响应先校验关键字段再交给页面，异常响应不能伪装成空列表或已读成功；
 * - 公告使用 `announcement:{id}` 作为列表键，删除/已读也沿用该键，不能交给点评定位接口；
 * - 通知里不保存点评正文，回复详情必须通过专用接口重新鉴权读取。
 */

export type NotificationCategory = 'ALL' | 'SYSTEM' | 'COMMENTS' | 'LIKES' | 'FOLLOWERS'

export type NotificationType =
  | 'ANNOUNCEMENT'
  | 'REPORT_RESULT'
  | 'USER_FOLLOWED'
  | 'REVIEW_LIKED'
  | 'REPLY_LIKED'
  | 'REVIEW_REPLIED'
  | string

export interface NotificationItem {
  id: string
  category: Exclude<NotificationCategory, 'ALL'>
  notificationType: NotificationType | null
  sourceId: string
  reviewId: string | null
  actorUserId: string | null
  actorUsername: string | null
  requestId: string | null
  requestType: 'CREATE' | 'UPDATE' | null
  decision: 'APPROVED' | 'REJECTED' | 'REMOVED' | 'DISMISSED' | null
  restaurantName: string | null
  rejectReason: string | null
  announcementId: string | null
  announcementTitle: string | null
  createdAt: string
  readAt: string | null
}

export interface NotificationPage {
  userId: string
  category: NotificationCategory
  records: NotificationItem[]
  total: number
  unreadCount: number
  unreadCounts: {
    SYSTEM: number
    COMMENTS: number
    LIKES: number
    FOLLOWERS: number
  }
  latestSystem: NotificationItem | null
  totalPages: number
  page: number
  pageSize: number
}

/** 页面直接消费的展示模型，避免 WXML 里堆叠判断分支。 */
export interface NotificationView extends NotificationItem {
  headline: string
  summary: string
  hint: string
  timeLabel: string
  unread: boolean
}

export const PAGE_SIZE = 10

const CATEGORY_LABELS: Record<NotificationCategory, string> = {
  ALL: '全部',
  SYSTEM: '系统',
  COMMENTS: '回复',
  LIKES: '获赞',
  FOLLOWERS: '关注',
}

export function categoryLabel(category: NotificationCategory): string {
  return CATEGORY_LABELS[category]
}

const ID_PATTERN = /^[1-9]\d{0,18}$/
const MAX_ID = 9223372036854775807n

/** 业务 ID 为无前导零的正 Long 十进制字符串，超出范围视为异常响应。 */
function isId(value: unknown): value is string {
  return typeof value === 'string' && ID_PATTERN.test(value) && BigInt(value) <= MAX_ID
}

function isDate(value: unknown): value is string {
  return typeof value === 'string' && Number.isFinite(Date.parse(value))
}

function isCount(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

function invalidResponse(): Error {
  return new Error('消息资料无法确认，请重新加载。')
}

const CATEGORIES: NotificationItem['category'][] = [
  'SYSTEM',
  'COMMENTS',
  'LIKES',
  'FOLLOWERS',
]

/** 按消息类型校验字段组合；公告必须带类型前缀，回复不得混入审核快照。 */
function toItem(row: unknown): NotificationItem {
  if (!row || typeof row !== 'object') throw invalidResponse()
  const value = row as Partial<NotificationItem>
  const announcement = value.notificationType === 'ANNOUNCEMENT'
  const reportResult = value.notificationType === 'REPORT_RESULT'

  if (announcement) {
    if (
      !isId(value.announcementId) ||
      value.id !== `announcement:${value.announcementId}` ||
      value.category !== 'SYSTEM' ||
      value.sourceId !== value.announcementId ||
      typeof value.announcementTitle !== 'string' ||
      !value.announcementTitle.trim()
    ) {
      throw invalidResponse()
    }
  } else if (reportResult) {
    if (
      value.category !== 'SYSTEM' ||
      !isId(value.reviewId) ||
      (value.decision !== 'REMOVED' && value.decision !== 'DISMISSED')
    ) {
      throw invalidResponse()
    }
  } else if (value.category === 'SYSTEM') {
    if (
      !isId(value.requestId) ||
      value.sourceId !== value.requestId ||
      (value.requestType !== 'CREATE' && value.requestType !== 'UPDATE') ||
      (value.decision !== 'APPROVED' && value.decision !== 'REJECTED') ||
      typeof value.restaurantName !== 'string' ||
      !value.restaurantName.trim()
    ) {
      throw invalidResponse()
    }
  } else if (value.category === 'COMMENTS' || value.category === 'LIKES') {
    if (
      !isId(value.reviewId) ||
      !isId(value.actorUserId) ||
      typeof value.actorUsername !== 'string' ||
      !value.actorUsername.trim()
    ) {
      throw invalidResponse()
    }
    if (
      value.category === 'LIKES' &&
      value.notificationType !== 'REVIEW_LIKED' &&
      value.notificationType !== 'REPLY_LIKED'
    ) {
      throw invalidResponse()
    }
  } else if (value.category === 'FOLLOWERS') {
    if (
      value.notificationType !== 'USER_FOLLOWED' ||
      !isId(value.actorUserId) ||
      typeof value.actorUsername !== 'string' ||
      !value.actorUsername.trim()
    ) {
      throw invalidResponse()
    }
  } else {
    throw invalidResponse()
  }

  if (!isId(value.id) && !announcement) throw invalidResponse()
  if (!isId(value.sourceId) || !isDate(value.createdAt)) throw invalidResponse()
  if (
    value.readAt !== null &&
    value.readAt !== undefined &&
    (!isDate(value.readAt) || Date.parse(value.readAt) < Date.parse(value.createdAt))
  ) {
    throw invalidResponse()
  }

  return {
    id: value.id as string,
    category: value.category as NotificationItem['category'],
    notificationType: value.notificationType ?? null,
    sourceId: value.sourceId,
    reviewId: value.reviewId ?? null,
    actorUserId: value.actorUserId ?? null,
    actorUsername: value.actorUsername ?? null,
    requestId: value.requestId ?? null,
    requestType: value.requestType ?? null,
    decision: value.decision ?? null,
    restaurantName: value.restaurantName ?? null,
    rejectReason: value.rejectReason ?? null,
    announcementId: value.announcementId ?? null,
    announcementTitle: value.announcementTitle ?? null,
    createdAt: value.createdAt,
    readAt: value.readAt ?? null,
  }
}

function reportDecisionText(item: NotificationItem): string {
  return item.decision === 'REMOVED' ? '举报已处理：内容已删除' : '举报未认定违规'
}

/** 生成列表与详情的统一文案；未知类型保持系统通知，不臆造来源。 */
export function toNotificationView(item: NotificationItem): NotificationView {
  const unread = item.readAt === null
  let headline = '系统通知'
  let summary = ''
  let hint = '查看详情'
  if (item.category === 'COMMENTS') {
    headline = `${item.actorUsername ?? '用户'} 回复了你的点评`
    summary = '打开可查看原点评与回复内容'
    hint = '查看点评与回复'
  } else if (item.category === 'LIKES') {
    headline = `${item.actorUsername ?? '用户'} 赞了你的${
      item.notificationType === 'REPLY_LIKED' ? '回复' : '点评'
    }`
    summary = '获赞内容仍需按原权限重新读取'
    hint = '查看获赞提醒'
  } else if (item.category === 'FOLLOWERS') {
    headline = `${item.actorUsername ?? '用户'} 关注了你`
    summary = '个人主页与关注名单暂未在小程序开放'
    hint = '查看关注提醒'
  } else if (item.notificationType === 'ANNOUNCEMENT') {
    headline = '系统公告'
    summary = item.announcementTitle ?? ''
    hint = '查看公告'
  } else if (item.notificationType === 'REPORT_RESULT') {
    headline = '举报处理结果'
    summary = reportDecisionText(item)
    hint = '查看处理结果'
  } else {
    headline = `${item.requestType === 'CREATE' ? '新店投稿' : '资料纠错'}${
      item.decision === 'APPROVED' ? '审核通过' : '审核未通过'
    }`
    summary = item.restaurantName ?? ''
    hint = '查看审核结果'
  }
  return {
    ...item,
    headline,
    summary,
    hint,
    timeLabel: formatDate(item.createdAt),
    unread,
  }
}

function currentUserScope(): { userId: string; token: string } {
  const user = getCurrentUser()
  const token = getToken()
  if (!user || !token) {
    throw new ApiError('请先登录。', 'AUTH_UNAUTHENTICATED', 401)
  }
  return { userId: String(user.id), token }
}

function assertIdentity(userId: string): void {
  const user = getCurrentUser()
  if (!user || String(user.id) !== userId || !getToken()) {
    throw new ApiError('登录状态已变化，请重新登录。', 'AUTH_UNAUTHENTICATED', 401)
  }
}

/**
 * 读取一页收件箱。
 *
 * 列表、分类计数、各类未读数和最新系统摘要来自服务端同一快照；
 * 浏览不标记已读，前端也不能根据当前页自行推算未读数。
 */
export async function fetchNotifications(
  category: NotificationCategory,
  page: number,
): Promise<NotificationPage> {
  const { userId } = currentUserScope()
  const raw = await request<NotificationPage>({
    path: `/api/me/notifications?category=${category}&page=${page}&pageSize=${PAGE_SIZE}`,
    auth: 'required',
  })
  if (
    !raw ||
    raw.userId !== userId ||
    raw.category !== category ||
    raw.page !== page ||
    raw.pageSize !== PAGE_SIZE ||
    !isCount(raw.total) ||
    !isCount(raw.unreadCount) ||
    raw.unreadCount > raw.total ||
    !isCount(raw.totalPages) ||
    raw.totalPages !== Math.ceil(raw.total / PAGE_SIZE) ||
    !raw.unreadCounts ||
    !isCount(raw.unreadCounts.SYSTEM) ||
    !isCount(raw.unreadCounts.COMMENTS) ||
    (raw.unreadCounts.LIKES !== undefined && !isCount(raw.unreadCounts.LIKES)) ||
    (raw.unreadCounts.FOLLOWERS !== undefined && !isCount(raw.unreadCounts.FOLLOWERS)) ||
    !Array.isArray(raw.records) ||
    raw.records.length > PAGE_SIZE
  ) {
    throw invalidResponse()
  }
  const unreadCounts = {
    SYSTEM: raw.unreadCounts.SYSTEM,
    COMMENTS: raw.unreadCounts.COMMENTS,
    LIKES: raw.unreadCounts.LIKES ?? 0,
    FOLLOWERS: raw.unreadCounts.FOLLOWERS ?? 0,
  }
  const totalUnread =
    unreadCounts.SYSTEM + unreadCounts.COMMENTS + unreadCounts.LIKES + unreadCounts.FOLLOWERS
  if (raw.unreadCount !== (category === 'ALL' ? totalUnread : unreadCounts[category])) {
    throw invalidResponse()
  }
  const records = raw.records.map(toItem)
  const latestSystem = raw.latestSystem ? toItem(raw.latestSystem) : null
  if (
    new Set(records.map((item) => item.id)).size !== records.length ||
    records.length > raw.total ||
    records.some((item) => category !== 'ALL' && item.category !== category) ||
    (latestSystem !== null && latestSystem.category !== 'SYSTEM')
  ) {
    throw invalidResponse()
  }
  return {
    userId,
    category,
    records,
    total: raw.total,
    unreadCount: raw.unreadCount,
    unreadCounts,
    latestSystem,
    totalPages: raw.totalPages,
    page,
    pageSize: PAGE_SIZE,
  }
}

/** 轻量未读数，用于悬浮底部导航红点；失败时保留旧值，不影响页面内容。 */
export async function fetchUnreadCount(): Promise<number> {
  const { userId } = currentUserScope()
  const raw = await request<{ userId: string; unreadCount: number }>({
    path: '/api/me/notifications/unread',
    auth: 'required',
  })
  if (!raw || raw.userId !== userId || !isCount(raw.unreadCount)) throw invalidResponse()
  return raw.unreadCount
}

/** 单条已读只改变本人该条消息；公告沿用 `announcement:{id}` 键。 */
export async function markNotificationRead(id: string): Promise<void> {
  currentUserScope()
  if (!isNotificationKey(id)) throw new Error('消息编号无法确认。')
  await request<undefined>({
    path: `/api/me/notifications/${encodeURIComponent(id)}/read`,
    method: 'POST',
    auth: 'required',
  })
}

/** 全部已读按当前分类执行；空收件箱也可调用，不删除消息。 */
export async function markAllNotificationsRead(
  category: NotificationCategory,
): Promise<void> {
  currentUserScope()
  await request<undefined>({
    path: `/api/me/notifications/read-all?category=${category}`,
    method: 'POST',
    auth: 'required',
  })
}

/** 删除本人单条消息；成功返回空结果，失败交由页面保留原消息并提示。 */
export async function deleteNotification(id: string): Promise<void> {
  currentUserScope()
  if (!isNotificationKey(id)) throw new Error('消息编号无法确认。')
  await request<undefined>({
    path: `/api/me/notifications/${encodeURIComponent(id)}`,
    method: 'DELETE',
    auth: 'required',
  })
}

function isNotificationKey(id: string): boolean {
  if (id.startsWith('announcement:')) return isId(id.slice('announcement:'.length))
  return isId(id)
}

export interface AnnouncementDetail {
  id: string
  title: string
  content: string
}

/** 公告正文只在打开详情时读取，列表接口不返回正文。 */
export async function fetchAnnouncement(announcementId: string): Promise<AnnouncementDetail> {
  currentUserScope()
  if (!isId(announcementId)) throw new Error('公告编号无法确认。')
  const raw = await request<{
    id: string
    title: string
    content: string
    status: string
  }>({
    path: `/api/me/announcements/${encodeURIComponent(announcementId)}`,
    auth: 'required',
  })
  if (
    !raw ||
    raw.id !== announcementId ||
    typeof raw.title !== 'string' ||
    !raw.title.trim() ||
    typeof raw.content !== 'string' ||
    !raw.content.trim()
  ) {
    throw invalidResponse()
  }
  return { id: raw.id, title: raw.title, content: raw.content }
}

export interface ReplyTarget {
  reviewId: string
  replyId: string
  restaurantId: string | null
  windowId: string | null
  reviewAuthor: string
  replyAuthor: string
  reviewBody: string
  replyBody: string
  createdAt: string
}

/**
 * 按回复编号重新读取原点评与回复。
 *
 * 通知不保存正文，也不能接受任意跳转地址；这里同时核验点评编号与当前用户，
 * 源内容已删除时由页面统一显示“内容已失效或不可访问”。
 */
export async function fetchReplyTarget(
  replyId: string,
  reviewId: string,
): Promise<ReplyTarget> {
  const { userId } = currentUserScope()
  if (!isId(replyId) || !isId(reviewId)) throw new Error('回复编号无法确认。')
  const raw = await request<{
    userId: string
    restaurantId: string | null
    windowId?: string | null
    reviewId: string
    replyId: string
    reviewAuthor: { id: string; username: string }
    replyAuthor: { id: string; username: string }
    review: { id: string; body: string; createdAt: string }
    replyBody: string
    replyCreatedAt: string
  }>({
    path: `/api/me/review-replies/${encodeURIComponent(replyId)}/target`,
    auth: 'required',
  })
  if (
    !raw ||
    raw.userId !== userId ||
    raw.replyId !== replyId ||
    raw.reviewId !== reviewId ||
    (raw.restaurantId !== null && !isId(raw.restaurantId)) ||
    (raw.windowId !== undefined &&
      raw.windowId !== null &&
      !isId(raw.windowId)) ||
    !raw.review ||
    raw.review.id !== reviewId ||
    typeof raw.review.body !== 'string' ||
    !isDate(raw.review.createdAt) ||
    !raw.reviewAuthor ||
    raw.reviewAuthor.id !== userId ||
    typeof raw.reviewAuthor.username !== 'string' ||
    !raw.replyAuthor ||
    typeof raw.replyAuthor.username !== 'string' ||
    typeof raw.replyBody !== 'string' ||
    !isDate(raw.replyCreatedAt)
  ) {
    throw invalidResponse()
  }
  return {
    reviewId,
    replyId,
    restaurantId: raw.restaurantId,
    windowId: raw.windowId ?? null,
    reviewAuthor: raw.reviewAuthor.username,
    replyAuthor: raw.replyAuthor.username,
    reviewBody: raw.review.body,
    replyBody: raw.replyBody,
    createdAt: raw.replyCreatedAt,
  }
}

/** 统一把接口异常转成可展示文案；内容失效单独提示，不暴露内部错误。 */
export function messageErrorText(reason: unknown): string {
  if (reason instanceof ApiError && reason.status === 404) {
    return '内容已失效或不可访问。'
  }
  return toErrorMessage(reason)
}
