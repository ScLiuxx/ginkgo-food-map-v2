import type { ApiResponse } from '@/shared/api/api.types'
import { httpClient } from '@/shared/api/httpClient'

/** 本人资料只持有脱敏邮箱，不能当作公开他人资料接口使用。 */
export interface OwnProfile {
  userId: string
  username: string
  maskedEmail: string
  bio: string
}
function profile(response: ApiResponse<OwnProfile>, userId: string): OwnProfile {
  const value = response.data
  if (
    !response.success ||
    !value ||
    value.userId !== userId ||
    typeof value.username !== 'string' ||
    typeof value.maskedEmail !== 'string' ||
    !value.maskedEmail.includes('*') ||
    typeof value.bio !== 'string' ||
    Array.from(value.bio).length > 100
  )
    throw new Error('个人资料无法确认，请重新加载。')
  // 明确选择字段，不把未来响应中意外附带的私密列复制到页面状态。
  return {
    userId: value.userId,
    username: value.username,
    maskedEmail: value.maskedEmail,
    bio: value.bio,
  }
}
export async function fetchOwnProfile(
  userId: string,
  token: string,
  signal: AbortSignal,
): Promise<OwnProfile> {
  const result = await httpClient.get<ApiResponse<OwnProfile>>('/api/me/profile', {
    signal,
    headers: { Authorization: `Bearer ${token}` },
  })
  return profile(result.data, userId)
}
/** 绝对值更新可用同一简介重试；只发送bio，身份始终来自固定请求令牌。 */
export async function updateOwnBio(
  userId: string,
  token: string,
  bio: string,
  signal: AbortSignal,
): Promise<OwnProfile> {
  const result = await httpClient.patch<ApiResponse<OwnProfile>>(
    '/api/me/profile',
    { bio },
    { signal, headers: { Authorization: `Bearer ${token}` } },
  )
  const saved = profile(result.data, userId)
  if (saved.bio !== bio) throw new Error('简介保存结果无法确认，请刷新核对后重试。')
  return saved
}
