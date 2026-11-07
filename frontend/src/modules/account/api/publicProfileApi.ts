import { httpClient } from '@/shared/api/httpClient'
import type { ApiResponse } from '@/shared/api/api.types'

export interface PublicProfile {
  userId: string
  username: string
  bio: string
  level: number
  receivedLikes: number
}
/** 固定会话与查看对象，逐项提取公开字段，不保留意外返回的私有字段。 */
export async function fetchPublicProfile(
  userId: string,
  token: string,
  signal: AbortSignal,
): Promise<PublicProfile> {
  const response = await httpClient.get<ApiResponse<PublicProfile>>(
    `/api/users/${encodeURIComponent(userId)}/profile`,
    {
      headers: { Authorization: `Bearer ${token}` },
      signal,
    },
  )
  const data = response.data.data
  if (
    !response.data.success ||
    !data ||
    data.userId !== userId ||
    typeof data.username !== 'string' ||
    typeof data.bio !== 'string' ||
    !Number.isSafeInteger(data.level) ||
    data.level < 1 ||
    !Number.isSafeInteger(data.receivedLikes) ||
    data.receivedLikes < 0
  )
    throw new Error('个人主页资料无法确认，请重新加载。')
  return {
    userId: data.userId,
    username: data.username,
    bio: data.bio,
    level: data.level,
    receivedLikes: data.receivedLikes,
  }
}
