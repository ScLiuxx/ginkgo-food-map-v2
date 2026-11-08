import { httpClient } from '@/shared/api/httpClient'
import type { ApiResponse } from '@/shared/api/api.types'

export interface ReplyTarget {
  userId: string
  restaurantId: string | null
  windowId?: string | null
  reviewId: string
  replyId: string
  reviewAuthor: { id: string; username: string }
  replyAuthor: { id: string; username: string }
  review: {
    id: string
    body: string
    createdAt: string
    overallScore: number
    recommended: boolean
  }
  replyBody: string
  replyCreatedAt: string
}
const id = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^[1-9]\d{0,18}$/.test(value) &&
  BigInt(value) <= 9223372036854775807n
const date = (value: unknown) => typeof value === 'string' && Number.isFinite(Date.parse(value))

/** 源内容重新鉴权，不从通知恢复正文；同时核验目标回复与点评，防止返回错条记录。 */
export async function fetchReplyTarget(
  replyId: string,
  reviewId: string,
  userId: string,
  token: string,
  signal: AbortSignal,
): Promise<ReplyTarget> {
  if (!id(replyId) || !id(reviewId)) throw new Error('评论目标无法确认。')
  const response = await httpClient.get<ApiResponse<ReplyTarget>>(
    `/api/me/review-replies/${replyId}/target`,
    { signal, headers: { Authorization: `Bearer ${token}` } },
  )
  const data = response.data.data
  if (
    !response.data.success ||
    !data ||
    data.userId !== userId ||
    data.replyId !== replyId ||
    data.reviewId !== reviewId ||
    !(
      (id(data.restaurantId) && !data.windowId) ||
      (data.restaurantId === null && id(data.windowId))
    ) ||
    !data.review ||
    data.review.id !== reviewId ||
    typeof data.review.body !== 'string' ||
    !date(data.review.createdAt) ||
    !Number.isFinite(data.review.overallScore) ||
    data.review.overallScore < 0 ||
    data.review.overallScore > 10 ||
    typeof data.review.recommended !== 'boolean' ||
    !data.reviewAuthor ||
    data.reviewAuthor.id !== userId ||
    typeof data.reviewAuthor.username !== 'string' ||
    !data.replyAuthor ||
    !id(data.replyAuthor.id) ||
    typeof data.replyAuthor.username !== 'string' ||
    typeof data.replyBody !== 'string' ||
    !date(data.replyCreatedAt)
  )
    throw new Error('评论目标无法确认，请重新加载。')
  return {
    userId,
    restaurantId: data.restaurantId,
    windowId: data.windowId,
    reviewId,
    replyId,
    reviewAuthor: {
      id: data.reviewAuthor.id,
      username: data.reviewAuthor.username,
    },
    replyAuthor: {
      id: data.replyAuthor.id,
      username: data.replyAuthor.username,
    },
    review: {
      id: reviewId,
      body: data.review.body,
      createdAt: data.review.createdAt,
      overallScore: data.review.overallScore,
      recommended: data.review.recommended,
    },
    replyBody: data.replyBody,
    replyCreatedAt: data.replyCreatedAt,
  }
}
