import type { ApiResponse } from '@/shared/api/api.types'
import { httpClient } from '@/shared/api/httpClient'
import type { ReviewItem, Scores } from '../model/review'

export interface MyReviewGroup {
  restaurant: { id: string; name: string; businessStatus: 'OPERATING' | 'CLOSED' }
  item: ReviewItem
}
export interface MyReviewPage {
  author: { id: string; username: string; level?: number }
  records: MyReviewGroup[]
  totalGroups: number
  totalReviews: number
  page: number
  pageSize: number
  totalPages: number
}
interface MyReviewPageDto extends Omit<MyReviewPage, 'records'> {
  records: {
    restaurant: MyReviewGroup['restaurant']
    currentReview: {
      id: string
      scores: Scores
      overallScore: number
      recommended: boolean
      body: string
      createdAt: string
      scoringStatus: 'current'
      photos: { id: string; position: number; url: string }[]
    }
    historyCount: number
    nextHistoryCursor: string | null
  }[]
}
const validId = (value: unknown): value is string =>
  typeof value === 'string' && /^[1-9]\d*$/.test(value)

/** 固定会话并核对返回作者；页码由本次操作决定，不传入userId或伪造offset。 */
export async function fetchMyReviews(
  page: number,
  userId: string,
  token: string,
  signal: AbortSignal,
  publicUserId?: string,
): Promise<MyReviewPage> {
  const response = await httpClient.get<ApiResponse<MyReviewPageDto>>(
    publicUserId ? `/api/users/${encodeURIComponent(publicUserId)}/reviews` : '/api/me/reviews',
    {
      params: { page, pageSize: 8 },
      signal,
      headers: { Authorization: `Bearer ${token}` },
    },
  )
  const data = response.data.data
  const invalid = () => new Error('我的点评数据无法确认，请重新加载。')
  if (
    !response.data.success ||
    !data ||
    !data.author ||
    data.author.id !== userId ||
    typeof data.author.username !== 'string' ||
    data.page !== page ||
    data.pageSize !== 8 ||
    ![data.totalGroups, data.totalReviews, data.totalPages].every(
      (value) => Number.isSafeInteger(value) && value >= 0,
    ) ||
    data.totalReviews < data.totalGroups ||
    data.totalPages !== Math.ceil(data.totalGroups / 8) ||
    !Array.isArray(data.records) ||
    data.records.length > 8 ||
    data.records.length > data.totalGroups
  )
    throw invalid()
  const groups = new Set<string>()
  const reviewIds = new Set<string>()
  const records = data.records.map((group) => {
    const review = group.currentReview
    const restaurant = group.restaurant
    if (
      !restaurant ||
      !validId(restaurant.id) ||
      groups.has(restaurant.id) ||
      typeof restaurant.name !== 'string' ||
      !['OPERATING', 'CLOSED'].includes(restaurant.businessStatus) ||
      !review ||
      !validId(review.id) ||
      reviewIds.has(review.id) ||
      review.scoringStatus !== 'current' ||
      typeof review.body !== 'string' ||
      typeof review.recommended !== 'boolean' ||
      !Number.isFinite(Date.parse(review.createdAt)) ||
      !Number.isFinite(review.overallScore) ||
      review.overallScore < 0 ||
      review.overallScore > 10 ||
      !review.scores ||
      !['taste', 'value', 'service', 'ambience'].every((key) => {
        const value = review.scores[key as keyof Scores]
        return typeof value === 'number' && value >= 0 && value <= 10
      }) ||
      !Number.isSafeInteger(group.historyCount) ||
      group.historyCount < 0 ||
      (group.historyCount > 0
        ? typeof group.nextHistoryCursor !== 'string' ||
          !/^[A-Za-z0-9_-]{1,200}$/.test(group.nextHistoryCursor)
        : group.nextHistoryCursor != null) ||
      !Array.isArray(review.photos) ||
      review.photos.length > 5 ||
      !review.photos.every(
        (photo, index) =>
          validId(photo.id) &&
          photo.position === index &&
          photo.url === `/api/reviews/${review.id}/photos/${index}`,
      )
    )
      throw invalid()
    groups.add(restaurant.id)
    reviewIds.add(review.id)
    return {
      restaurant,
      item: {
        ...review,
        authorId: data.author.id,
        username: data.author.username,
        authorLevel: data.author.level,
        historyCount: group.historyCount,
        nextHistoryCursor: group.nextHistoryCursor,
        photos: review.photos.map((photo) => ({ id: photo.id, url: photo.url })),
        canDelete: false,
        replies: [],
      },
    }
  })
  return { ...data, records }
}
