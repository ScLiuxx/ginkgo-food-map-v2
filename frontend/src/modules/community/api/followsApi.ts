import { httpClient } from '@/shared/api/httpClient'
import type { ApiResponse } from '@/shared/api/api.types'
export type FollowKind = 'FOLLOWING' | 'FOLLOWERS'
export interface FollowSummary {
  userId: string
  followingCount: number
  followersCount: number
  following: boolean
}
export interface FollowPrivacy {
  userId: string
  followingPublic: boolean
  followersPublic: boolean
}
export interface FollowMember {
  userId: string
  username: string
  level: number | null
  following: boolean
}
export interface FollowPage {
  userId: string
  kind: FollowKind
  records: FollowMember[]
  total: number
  totalPages: number
  page: number
  pageSize: number
}
const options = (token: string, signal?: AbortSignal) => ({
  headers: { Authorization: `Bearer ${token}` },
  signal,
})
const count = (value: unknown): value is number =>
  Number.isSafeInteger(value) && (value as number) >= 0
const id = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^[1-9]\d{0,18}$/.test(value) &&
  BigInt(value) <= 9223372036854775807n
const invalid = () => new Error('关注资料无法确认，请重新读取。')
function summary(data: FollowSummary, userId: string): FollowSummary {
  if (
    !data ||
    data.userId !== userId ||
    !count(data.followingCount) ||
    !count(data.followersCount) ||
    typeof data.following !== 'boolean'
  )
    throw invalid()
  return {
    userId,
    followingCount: data.followingCount,
    followersCount: data.followersCount,
    following: data.following,
  }
}
/** 固定访问者凭证与查看主体，失败不伪造未关注或零人数。 */
export async function fetchFollowSummary(userId: string, token: string, signal: AbortSignal) {
  const response = await httpClient.get<ApiResponse<FollowSummary>>(
    `/api/users/${encodeURIComponent(userId)}/social`,
    options(token, signal),
  )
  if (!response.data.success) throw invalid()
  return summary(response.data.data, userId)
}
export async function setFollow(userId: string, active: boolean, token: string) {
  const path = `/api/users/${encodeURIComponent(userId)}/follow`
  const response = active
    ? await httpClient.put<ApiResponse<FollowSummary>>(path, undefined, options(token))
    : await httpClient.delete<ApiResponse<FollowSummary>>(path, options(token))
  if (!response.data.success) throw invalid()
  const result = summary(response.data.data, userId)
  if (result.following !== active) throw invalid()
  return result
}
/** 后端先校验名单隐私再计数分页，客户端不扫描其他页拼接全量名单。 */
export async function fetchFollowPage(
  userId: string,
  kind: FollowKind,
  page: number,
  token: string,
  signal: AbortSignal,
): Promise<FollowPage> {
  const response = await httpClient.get<ApiResponse<FollowPage>>(
    `/api/users/${encodeURIComponent(userId)}/${kind === 'FOLLOWING' ? 'following' : 'followers'}`,
    { ...options(token, signal), params: { page, pageSize: 8 } },
  )
  const data = response.data.data
  if (
    !response.data.success ||
    !data ||
    data.userId !== userId ||
    data.kind !== kind ||
    data.page !== page ||
    data.pageSize !== 8 ||
    !count(data.total) ||
    data.totalPages !== Math.ceil(data.total / 8) ||
    !Array.isArray(data.records) ||
    data.records.length > 8 ||
    data.records.length > data.total
  )
    throw invalid()
  const records = data.records.map((row) => {
    if (
      !id(row.userId) ||
      typeof row.username !== 'string' ||
      typeof row.following !== 'boolean' ||
      (row.level !== null && (!Number.isInteger(row.level) || row.level < 1))
    )
      throw invalid()
    return {
      userId: row.userId,
      username: row.username,
      following: row.following,
      level: row.level,
    }
  })
  if (new Set(records.map((row) => row.userId)).size !== records.length) throw invalid()
  return {
    userId,
    kind,
    records,
    total: data.total,
    totalPages: data.totalPages,
    page,
    pageSize: 8,
  }
}
export async function followPrivacy(
  userId: string,
  token: string,
  signal?: AbortSignal,
  value?: Pick<FollowPrivacy, 'followingPublic' | 'followersPublic'>,
): Promise<FollowPrivacy> {
  const path = '/api/me/follows/privacy'
  const response = value
    ? await httpClient.patch<ApiResponse<FollowPrivacy>>(path, value, options(token, signal))
    : await httpClient.get<ApiResponse<FollowPrivacy>>(path, options(token, signal))
  const data = response.data.data
  if (
    !response.data.success ||
    !data ||
    data.userId !== userId ||
    typeof data.followingPublic !== 'boolean' ||
    typeof data.followersPublic !== 'boolean' ||
    (value &&
      (data.followingPublic !== value.followingPublic ||
        data.followersPublic !== value.followersPublic))
  )
    throw invalid()
  return { userId, followingPublic: data.followingPublic, followersPublic: data.followersPublic }
}
