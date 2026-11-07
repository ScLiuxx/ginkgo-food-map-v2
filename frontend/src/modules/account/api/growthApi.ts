import type { ApiResponse } from '@/shared/api/api.types'
import { httpClient } from '@/shared/api/httpClient'

/** 本人成长快照；等级及门槛完全采信服务端，不在页面复制等级配置。 */
export interface GrowthSummary {
  userId: string
  experience: number
  level: number
  currentLevelThreshold: number
  nextLevelThreshold: number | null
}

/** 固定请求发出时的会话，切换账号不能把旧查询改成新账号的请求。 */
export async function fetchOwnGrowth(token: string, signal: AbortSignal): Promise<GrowthSummary> {
  const response = await httpClient.get<ApiResponse<GrowthSummary>>('/api/me/growth', {
    signal,
    headers: { Authorization: `Bearer ${token}` },
  })
  return response.data.data
}
