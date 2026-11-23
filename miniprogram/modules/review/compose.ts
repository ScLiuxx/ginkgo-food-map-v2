import { ApiError, request } from '../../shared/api/request'
import { isLongId } from '../../shared/lib/id'
import type { ReviewRecommendation } from './types'

/**
 * 点评发布：评分提交与幂等请求号；图片压缩上传由共用的文件能力提供。
 *
 * 约束来自后端发布接口：
 * - 四维评分 0–10，步进 0.5；
 * - 正文按去首尾空白后的 Unicode 码点计 1–500，不再要求至少 5 字；
 * - recommendation 必填三态，布尔旧字段会被服务端拒绝；
 * - 最多 5 张本人 REVIEW 用途临时图，单图不超过 300KiB；
 * - 相同 clientRequestId 只在内容完全一致时重放，内容变化必须换新编号。
 */

export const MAX_REVIEW_PHOTOS = 5
export const MAX_REVIEW_BODY = 500
const MIN_REVIEW_BODY = 1

export type ScoreKey = 'taste' | 'value' | 'service' | 'ambience'

export interface ReviewScores {
  taste: number
  value: number
  service: number
  ambience: number
}

/** 发布输入；餐厅与食堂都使用自身 restaurantId，直接点评主体。 */
export interface ReviewPublishInput {
  clientRequestId: string
  scores: ReviewScores
  recommendation: ReviewRecommendation
  body: string
  photoIds: string[]
}

/** 发布回执；餐厅与食堂共用同一入口，因此只回指 restaurantId。 */
export interface ReviewPublishReceipt {
  reviewId: string
  restaurantId: string
  replayed: boolean
}

/** 去掉首尾空白后按 Unicode 码点计数，与后端“码点”口径一致。 */
export function reviewBodyLength(value: string): number {
  return Array.from(value.trim()).length
}

export function normalizeReviewBody(value: string): string {
  return value.trim()
}

export function isValidScore(value: number): boolean {
  return Number.isFinite(value) && value >= 0 && value <= 10 && Number.isInteger(value * 2)
}

export function isValidScores(scores: ReviewScores): boolean {
  return (['taste', 'value', 'service', 'ambience'] as ScoreKey[]).every((key) =>
    isValidScore(scores[key]),
  )
}

/**
 * 发布前按服务端同一规则校验，返回规范化后的正文。
 *
 * 这里只减少明显无效的请求，授权、店铺存在性、图片归属和内容冲突
 * 仍由服务端最终判定，前端不能因为本地通过就假定发布成功。
 */
function validatePublishInput(input: ReviewPublishInput): string {
  const body = normalizeReviewBody(input.body)
  const length = Array.from(body).length
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(input.clientRequestId)) {
    throw new Error('发布请求编号无法确认，请重新填写。')
  }
  if (!isValidScores(input.scores)) {
    throw new Error('评分需为 0–10 且步进 0.5。')
  }
  if (
    input.recommendation !== 'RECOMMENDED' &&
    input.recommendation !== 'NEUTRAL' &&
    input.recommendation !== 'NOT_RECOMMENDED'
  ) {
    throw new Error('推荐态度无法确认。')
  }
  if (length < MIN_REVIEW_BODY || length > MAX_REVIEW_BODY) {
    throw new Error(`请填写 ${MIN_REVIEW_BODY}–${MAX_REVIEW_BODY} 字的点评正文。`)
  }
  if (
    input.photoIds.length > MAX_REVIEW_PHOTOS ||
    new Set(input.photoIds).size !== input.photoIds.length ||
    input.photoIds.some((id) => !isLongId(id))
  ) {
    throw new Error('点评照片数量或编号不符合要求。')
  }
  return body
}

/** 餐厅与食堂请求体完全一致，食堂也直接使用自身 restaurantId。 */
function publishPayload(input: ReviewPublishInput, body: string) {
  return {
    clientRequestId: input.clientRequestId,
    scores: {
      taste: input.scores.taste,
      value: input.scores.value,
      service: input.scores.service,
      ambience: input.scores.ambience,
    },
    recommendation: input.recommendation,
    body,
    photoIds: input.photoIds,
  }
}

/**
 * 发布点评。
 *
 * 响应必须回指本次 restaurantId，避免错误路由的回执被当成发布成功；
 * 服务端仍是最终校验与授权来源。
 */
export async function publishReview(
  restaurantId: string,
  input: ReviewPublishInput,
): Promise<ReviewPublishReceipt> {
  const body = validatePublishInput(input)
  if (!isLongId(restaurantId)) {
    throw new Error('店铺编号无效，无法发布点评。')
  }

  const raw = await request<{
    reviewId: string
    restaurantId: string
    replayed: boolean
  }>({
    path: `/api/restaurants/${encodeURIComponent(restaurantId)}/reviews`,
    method: 'POST',
    auth: 'required',
    data: publishPayload(input, body),
  })
  if (
    !raw ||
    !isLongId(raw.reviewId) ||
    raw.restaurantId !== restaurantId ||
    typeof raw.replayed !== 'boolean'
  ) {
    throw new ApiError('发布结果无法确认，请稍后核对点评列表。', 'INVALID_RESPONSE', 200)
  }
  return {
    reviewId: raw.reviewId,
    restaurantId: raw.restaurantId,
    replayed: raw.replayed,
  }
}
