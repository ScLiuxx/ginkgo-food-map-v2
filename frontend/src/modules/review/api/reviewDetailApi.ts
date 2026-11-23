import { httpClient } from '@/shared/api/httpClient'
import type { ApiResponse } from '@/shared/api/api.types'
import { fetchReplyTarget } from './replyTargetApi'
import {
  isReviewRecommendation,
  type ReviewItem,
  type ReplyPage,
  type Scores,
} from '../model/review'

export type ReviewNotificationType = 'COMMENT' | 'REVIEW_LIKED' | 'REPLY_LIKED'
export interface ReviewDetail {
  item: ReviewItem
  restaurantId: string
  focusedReply?: ReplyPage['records'][number]
}
const id = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^[1-9]\d{0,18}$/.test(value) &&
  BigInt(value) <= 9223372036854775807n
const date = (value: unknown): value is string =>
  typeof value === 'string' && Number.isFinite(Date.parse(value))
const invalid = () => new Error('点评详情无法确认，请重新加载。')
interface DetailDto {
  restaurantId: string
  author: { id: string; username: string; level?: number | null }
  review: {
    id: string
    scores: Scores
    overallScore: number
    recommendation: string
    body: string
    createdAt: string
    scoringStatus: 'current' | 'history'
    photos: Array<{ id: string; position: number; url: string }>
  }
}

/** 一次按稳定点评ID读取，核验餐厅与食堂归属；拒绝错配、任意图片URL及私有附带字段。 */
export async function fetchReviewDetail(
  reviewId: string,
  token: string,
  signal: AbortSignal,
): Promise<ReviewDetail> {
  if (!id(reviewId)) throw invalid()
  const response = await httpClient.get<ApiResponse<DetailDto>>(`/api/reviews/${reviewId}`, {
    signal,
    headers: { Authorization: `Bearer ${token}` },
  })
  const data = response.data.data,
    review = data?.review
  if (
    !response.data.success ||
    !data ||
    !review ||
    review.id !== reviewId ||
    !id(data.restaurantId) ||
    !id(data.author?.id) ||
    typeof data.author.username !== 'string' ||
    (data.author.level != null &&
      (!Number.isSafeInteger(data.author.level) || data.author.level < 1)) ||
    typeof review.body !== 'string' ||
    !date(review.createdAt) ||
    !isReviewRecommendation(review.recommendation) ||
    !['current', 'history'].includes(review.scoringStatus) ||
    !Number.isFinite(review.overallScore) ||
    review.overallScore < 0 ||
    review.overallScore > 10 ||
    !review.scores ||
    !['taste', 'value', 'service', 'ambience'].every((key) => {
      const value = review.scores[key as keyof Scores]
      return Number.isFinite(value) && value >= 0 && value <= 10 && Number.isInteger(value * 2)
    }) ||
    !Array.isArray(review.photos) ||
    review.photos.length > 5 ||
    !review.photos.every(
      (photo, index) =>
        photo &&
        id(photo.id) &&
        photo.position === index &&
        photo.url === `/api/reviews/${reviewId}/photos/${index}`,
    ) ||
    new Set(review.photos.map((photo) => photo.id)).size !== review.photos.length
  )
    throw invalid()
  return {
    restaurantId: data.restaurantId,
    item: {
      id: reviewId,
      authorId: data.author.id,
      username: data.author.username,
      authorLevel: data.author.level ?? undefined,
      scores: {
        taste: review.scores.taste,
        value: review.scores.value,
        service: review.scores.service,
        ambience: review.scores.ambience,
      },
      overallScore: review.overallScore,
      recommendation: review.recommendation,
      body: review.body,
      createdAt: review.createdAt,
      scoringStatus: review.scoringStatus,
      photos: review.photos.map((photo) => ({ id: photo.id, url: photo.url })),
      canDelete: false,
      replies: [],
    },
  }
}

/** 通知先按本人源记录重新鉴权，再按点评ID读取完整详情；任何错配均不退回店铺或替换最新点评。 */
export async function fetchNotificationReview(
  type: ReviewNotificationType,
  sourceId: string,
  reviewId: string,
  userId: string,
  token: string,
  signal: AbortSignal,
): Promise<ReviewDetail> {
  if (!id(sourceId) || !id(reviewId) || !id(userId)) throw invalid()
  let target: { restaurantId: string }
  let focusedReply: ReviewDetail['focusedReply']
  if (type === 'COMMENT') {
    const reply = await fetchReplyTarget(sourceId, reviewId, userId, token, signal)
    target = reply
    focusedReply = {
      id: reply.replyId,
      author: { ...reply.replyAuthor },
      body: reply.replyBody,
      createdAt: reply.replyCreatedAt,
    }
  } else {
    const response = await httpClient.get<
      ApiResponse<{
        userId: string
        likeId: string
        targetType: string
        targetId: string
        reviewId: string
        restaurantId: string
        author: { id: string; username: string }
        body: string
        createdAt: string
      }>
    >(`/api/me/received-likes/${sourceId}/target`, {
      signal,
      headers: { Authorization: `Bearer ${token}` },
    })
    const like = response.data.data
    if (
      !response.data.success ||
      !like ||
      like.userId !== userId ||
      like.likeId !== sourceId ||
      like.targetType !== (type === 'REPLY_LIKED' ? 'REPLY' : 'REVIEW') ||
      like.reviewId !== reviewId ||
      !id(like.targetId) ||
      (type === 'REVIEW_LIKED' && like.targetId !== reviewId) ||
      like.author?.id !== userId ||
      typeof like.author.username !== 'string' ||
      typeof like.body !== 'string' ||
      !date(like.createdAt)
    )
      throw invalid()
    target = like
    if (type === 'REPLY_LIKED')
      focusedReply = {
        id: like.targetId,
        author: { id: like.author.id, username: like.author.username },
        body: like.body,
        createdAt: like.createdAt,
      }
  }
  const detail = await fetchReviewDetail(reviewId, token, signal)
  if (
    detail.restaurantId !== target.restaurantId ||
    (type !== 'REPLY_LIKED' && detail.item.authorId !== userId)
  )
    throw invalid()
  return { ...detail, focusedReply }
}
