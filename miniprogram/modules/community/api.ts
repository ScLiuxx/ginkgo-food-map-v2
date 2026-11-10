import { request } from '../../shared/api/request'

export type FollowKind = 'FOLLOWING' | 'FOLLOWERS'

export interface PublicProfile {
  userId: string
  username: string
  bio: string
  level: number
  receivedLikes: number
}

export interface FollowSummary {
  userId: string
  followingCount: number
  followersCount: number
  following: boolean
}

export interface FollowMember {
  userId: string
  username: string
  /** 名单头像占位字符，避免在 WXML 中截取多字节用户名。 */
  avatarText: string
  level: number | null
  following: boolean
}

export interface FollowPageView {
  userId: string
  kind: FollowKind
  records: FollowMember[]
  total: number
  totalPages: number
  page: number
  pageSize: number
}

const FOLLOW_PAGE_SIZE = 8

function invalid(): Error {
  return new Error('关注资料无法确认，请重新加载。')
}

function validId(value: unknown): value is string {
  return typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value)
}

function count(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) >= 0
}

/**
 * 读取登录可见的公开主页资料。
 *
 * 路径中的用户是查看对象，不承载写身份；只提取白名单字段，
 * 即使服务端多返回邮箱或经验余额也不会进入页面模型。
 */
export async function fetchPublicProfile(userId: string): Promise<PublicProfile> {
  if (!validId(userId)) throw new Error('用户编号无效，请返回后重试。')
  const raw = await request<PublicProfile>({
    path: `/api/users/${encodeURIComponent(userId)}/profile`,
    auth: 'required',
  })
  if (
    !raw ||
    raw.userId !== userId ||
    typeof raw.username !== 'string' ||
    !raw.username.trim() ||
    typeof raw.bio !== 'string' ||
    !Number.isSafeInteger(raw.level) ||
    raw.level < 1 ||
    !Number.isSafeInteger(raw.receivedLikes) ||
    raw.receivedLikes < 0
  ) {
    throw invalid()
  }
  return {
    userId,
    username: raw.username,
    bio: raw.bio,
    level: raw.level,
    receivedLikes: raw.receivedLikes,
  }
}

/** 关注人数与当前关注状态来自同一份服务端快照，失败时不伪造零值。 */
export async function fetchFollowSummary(userId: string): Promise<FollowSummary> {
  if (!validId(userId)) throw new Error('用户编号无效，请返回后重试。')
  const raw = await request<FollowSummary>({
    path: `/api/users/${encodeURIComponent(userId)}/social`,
    auth: 'required',
  })
  if (
    !raw ||
    raw.userId !== userId ||
    !count(raw.followingCount) ||
    !count(raw.followersCount) ||
    typeof raw.following !== 'boolean'
  ) {
    throw invalid()
  }
  return {
    userId,
    followingCount: raw.followingCount,
    followersCount: raw.followersCount,
    following: raw.following,
  }
}

/**
 * 设置关注状态。
 *
 * 使用 PUT/DELETE 表达目标状态而不是翻转接口；响应必须回指同一用户
 * 且最终状态等于请求值，否则视为结果未确认而不是乐观更新界面。
 */
export async function setFollow(userId: string, active: boolean): Promise<FollowSummary> {
  if (!validId(userId)) throw new Error('用户编号无效，请返回后重试。')
  const raw = await request<FollowSummary>({
    path: `/api/users/${encodeURIComponent(userId)}/follow`,
    method: active ? 'PUT' : 'DELETE',
    auth: 'required',
  })
  if (
    !raw ||
    raw.userId !== userId ||
    raw.following !== active ||
    !count(raw.followingCount) ||
    !count(raw.followersCount)
  ) {
    throw invalid()
  }
  return {
    userId,
    followingCount: raw.followingCount,
    followersCount: raw.followersCount,
    following: raw.following,
  }
}

/**
 * 读取一页关注或粉丝名单。
 *
 * 名单是否私密由服务端判定（403 FOLLOW_LIST_PRIVATE），客户端不扫描其他页；
 * 只接受与请求用户、方向和页码一致的响应，避免分页错位后拼接错误名单。
 */
export async function fetchFollowPage(
  userId: string,
  kind: FollowKind,
  page: number,
): Promise<FollowPageView> {
  if (!validId(userId)) throw new Error('用户编号无效，请返回后重试。')
  const segment = kind === 'FOLLOWING' ? 'following' : 'followers'
  const raw = await request<FollowPageView>({
    path: `/api/users/${encodeURIComponent(userId)}/${segment}?page=${page}&pageSize=${FOLLOW_PAGE_SIZE}`,
    auth: 'required',
  })
  if (
    !raw ||
    raw.userId !== userId ||
    raw.kind !== kind ||
    raw.page !== page ||
    raw.pageSize !== FOLLOW_PAGE_SIZE ||
    !count(raw.total) ||
    !count(raw.totalPages) ||
    raw.totalPages !== Math.ceil(raw.total / FOLLOW_PAGE_SIZE) ||
    !Array.isArray(raw.records) ||
    raw.records.length > FOLLOW_PAGE_SIZE ||
    raw.records.length > raw.total
  ) {
    throw invalid()
  }
  const ids = new Set<string>()
  const records = raw.records.map((row): FollowMember => {
    if (
      !row ||
      !validId(row.userId) ||
      ids.has(row.userId) ||
      typeof row.username !== 'string' ||
      !row.username.trim() ||
      typeof row.following !== 'boolean' ||
      (row.level !== null && (!Number.isSafeInteger(row.level) || row.level < 1))
    ) {
      throw invalid()
    }
    ids.add(row.userId)
    return {
      userId: row.userId,
      username: row.username,
      avatarText: Array.from(row.username)[0]?.toUpperCase() ?? '?',
      level: row.level,
      following: row.following,
    }
  })
  return {
    userId,
    kind,
    records,
    total: raw.total,
    totalPages: raw.totalPages,
    page,
    pageSize: FOLLOW_PAGE_SIZE,
  }
}
