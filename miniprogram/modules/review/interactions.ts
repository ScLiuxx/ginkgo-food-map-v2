import { request } from '../../shared/api/request'
import { formatDate } from '../../shared/lib/format'

/** 点赞目标只有点评和单层回复两类。 */
export type LikeTargetType = 'REVIEW' | 'REPLY'

/** 举报原因与后端枚举保持一致，顺序即选择器顺序。 */
export const REPORT_REASONS: Array<{ value: ReportReason; label: string }> = [
  { value: 'SPAM', label: '广告营销' },
  { value: 'ABUSE', label: '辱骂攻击' },
  { value: 'PORNOGRAPHY', label: '色情低俗' },
  { value: 'ILLEGAL', label: '违法违规' },
  { value: 'PRIVACY', label: '泄露隐私' },
  { value: 'IRRELEVANT', label: '无关内容' },
  { value: 'OTHER', label: '其他' },
]

export type ReportReason =
  | 'SPAM'
  | 'ABUSE'
  | 'PORNOGRAPHY'
  | 'ILLEGAL'
  | 'PRIVACY'
  | 'IRRELEVANT'
  | 'OTHER'

export interface ReplyView {
  id: string
  authorId: string
  username: string
  avatarText: string
  body: string
  createdAtText: string
}

export interface ReplyPageView {
  records: ReplyView[]
  hasMore: boolean
  nextCursor: string
}

export interface ContentLikeState {
  targetId: string
  count: number
  liked: boolean
  available: boolean
}

interface ReplyRaw {
  id?: unknown
  author?: { id?: unknown; username?: unknown } | null
  body?: unknown
  createdAt?: unknown
}

interface ReplyPageRaw {
  records?: unknown
  hasMore?: unknown
  nextCursor?: unknown
}

interface LikeStateRaw {
  targetId?: unknown
  count?: unknown
  liked?: unknown
  available?: unknown
}

interface LikePageRaw {
  userId?: unknown
  type?: unknown
  records?: unknown
}

interface ReplyCreatedRaw {
  replyId?: unknown
  reviewId?: unknown
  replayed?: unknown
}

interface ReportCreatedRaw {
  reportId?: unknown
  targetType?: unknown
  targetId?: unknown
  status?: unknown
  duplicate?: unknown
  createdAt?: unknown
}

const invalid = (message: string) => new Error(message)

const LONG_MAX = '9223372036854775807'

function validId(value: unknown): value is string {
  // 编号按字符串比较长度，避免超过 Number.MAX_SAFE_INTEGER 的长整型被误判。
  return (
    typeof value === 'string' &&
    /^[1-9]\d*$/.test(value) &&
    (value.length < 19 || (value.length === 19 && value <= LONG_MAX))
  )
}

/** 回复列表按游标分页；每页 20 条，避免详情页一次拉取全部回复。 */
const REPLY_PAGE_SIZE = 20

/**
 * 读取一页单层回复。
 *
 * 回复作者可能已不可用，此时保留服务端“用户不可用”的占位语义，
 * 而不是丢弃该回复；其余字段异常仍整体拒绝，避免展示不完整内容。
 */
export async function fetchReplyPage(
  reviewId: string,
  cursor: string,
): Promise<ReplyPageView> {
  const suffix = cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''
  const raw = await request<ReplyPageRaw>({
    path: `/api/reviews/${encodeURIComponent(reviewId)}/replies?pageSize=${REPLY_PAGE_SIZE}${suffix}`,
    auth: 'none',
  })
  return toReplyPageView(raw)
}

function toReplyPageView(raw: ReplyPageRaw): ReplyPageView {
  if (
    !raw ||
    !Array.isArray(raw.records) ||
    raw.records.length > REPLY_PAGE_SIZE ||
    typeof raw.hasMore !== 'boolean' ||
    !(raw.nextCursor === null || raw.nextCursor === undefined || typeof raw.nextCursor === 'string') ||
    (typeof raw.nextCursor === 'string' && raw.nextCursor.length > 200) ||
    (raw.hasMore && typeof raw.nextCursor !== 'string')
  ) {
    throw invalid('回复数据无法确认，请重新加载。')
  }

  const ids = new Set<string>()
  const records = raw.records.map((value): ReplyView => {
    const reply = value as ReplyRaw
    if (!reply || !validId(reply.id) || ids.has(reply.id)) {
      throw invalid('回复数据无法确认，请重新加载。')
    }
    if (
      typeof reply.body !== 'string' ||
      Array.from(reply.body.trim()).length === 0 ||
      typeof reply.createdAt !== 'string' ||
      !Number.isFinite(Date.parse(reply.createdAt))
    ) {
      throw invalid('回复数据无法确认，请重新加载。')
    }
    ids.add(reply.id)

    const authorId = reply.author && validId(reply.author.id) ? reply.author.id : ''
    const username =
      reply.author && typeof reply.author.username === 'string' && reply.author.username.trim()
        ? reply.author.username
        : '用户不可用'
    return {
      id: reply.id,
      authorId,
      username,
      avatarText: Array.from(username)[0] ?? '?',
      body: reply.body,
      createdAtText: formatDate(reply.createdAt),
    }
  })

  return {
    records,
    hasMore: raw.hasMore,
    nextCursor: typeof raw.nextCursor === 'string' ? raw.nextCursor : '',
  }
}

/**
 * 批量读取点赞计数与本人状态。
 *
 * 公开接口可携带 Bearer 获取 liked；状态不跨账号缓存，页面每次加载都重新读取。
 * 缺失的目标不补 0，调用方保持“未知”状态，避免把查询失败伪装成无人点赞。
 */
export async function fetchLikeStates(
  type: LikeTargetType,
  targetIds: string[],
): Promise<Map<string, ContentLikeState>> {
  const ids = Array.from(new Set(targetIds))
  if (!ids.length) return new Map()
  if (ids.length > 50 || !ids.every(validId)) {
    throw invalid('点赞状态参数无效。')
  }

  const raw = await request<LikePageRaw>({
    path: `/api/content-likes?type=${type}&ids=${encodeURIComponent(ids.join(','))}`,
    auth: 'auto',
  })
  if (!raw || raw.type !== type || !Array.isArray(raw.records)) {
    throw invalid('点赞状态无法确认，请稍后重试。')
  }

  const wanted = new Set(ids)
  const result = new Map<string, ContentLikeState>()
  raw.records.forEach((value) => {
    const row = value as LikeStateRaw
    if (
      !row ||
      typeof row.targetId !== 'string' ||
      !wanted.has(row.targetId) ||
      result.has(row.targetId) ||
      typeof row.count !== 'number' ||
      !Number.isSafeInteger(row.count) ||
      row.count < 0 ||
      typeof row.liked !== 'boolean' ||
      typeof row.available !== 'boolean' ||
      (!row.available && (row.count !== 0 || row.liked))
    ) {
      throw invalid('点赞状态无法确认，请稍后重试。')
    }
    result.set(row.targetId, {
      targetId: row.targetId,
      count: row.count,
      liked: row.liked,
      available: row.available,
    })
  })
  return result
}

/** 设置点赞或取消点赞，以服务端回执为准更新按钮状态。 */
export async function setContentLike(
  type: LikeTargetType,
  targetId: string,
  liked: boolean,
): Promise<ContentLikeState> {
  const path =
    type === 'REVIEW'
      ? `/api/reviews/${encodeURIComponent(targetId)}/like`
      : `/api/review-replies/${encodeURIComponent(targetId)}/like`
  const raw = await request<LikeStateRaw>({
    path,
    method: liked ? 'PUT' : 'DELETE',
    auth: 'required',
  })
  if (
    !raw ||
    raw.targetId !== targetId ||
    typeof raw.count !== 'number' ||
    !Number.isSafeInteger(raw.count) ||
    raw.count < 0 ||
    raw.liked !== liked ||
    raw.available !== true
  ) {
    throw invalid('点赞结果无法确认，请重新加载后再试。')
  }
  return {
    targetId,
    count: raw.count,
    liked: raw.liked,
    available: true,
  }
}

/** 发布单层回复；同一请求号与内容由服务端重放，不重复创建。 */
export async function createReply(
  reviewId: string,
  body: string,
  clientRequestId: string,
): Promise<void> {
  const raw = await request<ReplyCreatedRaw>({
    path: `/api/reviews/${encodeURIComponent(reviewId)}/replies`,
    method: 'POST',
    data: { clientRequestId, body },
    auth: 'required',
  })
  if (!raw || !validId(raw.replyId) || raw.reviewId !== reviewId || typeof raw.replayed !== 'boolean') {
    throw invalid('回复结果无法确认，请刷新后核对。')
  }
}

/** 删除本人回复；重复删除同样返回 204，按成功处理。 */
export function deleteReply(replyId: string): Promise<void> {
  return request<void>({
    path: `/api/review-replies/${encodeURIComponent(replyId)}`,
    method: 'DELETE',
    auth: 'required',
  })
}

/** 提交内容举报；重复举报返回原记录，同样按已提交处理。 */
export async function submitContentReport(
  type: LikeTargetType,
  targetId: string,
  reason: ReportReason,
  detail: string,
): Promise<{ duplicate: boolean }> {
  const path =
    type === 'REVIEW'
      ? `/api/reviews/${encodeURIComponent(targetId)}/reports`
      : `/api/review-replies/${encodeURIComponent(targetId)}/reports`
  const raw = await request<ReportCreatedRaw>({
    path,
    method: 'POST',
    data: { reason, detail },
    auth: 'required',
  })
  if (
    !raw ||
    !validId(raw.reportId) ||
    raw.targetType !== type ||
    raw.targetId !== targetId ||
    (raw.status !== 'PENDING' && raw.status !== 'RESOLVED' && raw.status !== 'DISMISSED') ||
    typeof raw.duplicate !== 'boolean' ||
    // 首次提交必须仍在待处理；重复提交返回的可能是已处理的原记录。
    (!raw.duplicate && raw.status !== 'PENDING')
  ) {
    throw invalid('举报结果无法确认，请稍后重试。')
  }
  return { duplicate: raw.duplicate }
}
