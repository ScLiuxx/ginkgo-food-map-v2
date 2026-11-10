import { absoluteUrl } from '../../config/env'
import { formatDate, formatRating } from '../../shared/lib/format'

export type ReviewSort = 'DEFAULT' | 'TIME'

export interface ReviewScores {
  taste: number
  value: number
  service: number
  ambience: number
}

export interface ReviewPhotoRaw {
  id: string
  position: number
  url: string
}

export interface CurrentReviewRaw {
  id: string
  scores: ReviewScores
  overallScore: number
  recommended: boolean
  body: string
  createdAt: string
  scoringStatus: 'current' | 'history'
  photos: ReviewPhotoRaw[]
}

export interface ReviewGroupRaw {
  author: { id: string; username: string; level?: number }
  historyCount: number
  nextHistoryCursor?: string | null
  currentReview: CurrentReviewRaw
}

export interface ReviewGroupPageRaw {
  records: ReviewGroupRaw[]
  totalGroups: number
  totalReviews: number
  page: number
  pageSize: number
  totalPages: number
}

/** 页面只消费已校验的视图模型，WXML 中不再做函数调用或容错判断。 */
export interface ReviewView {
  id: string
  authorId: string
  username: string
  avatarText: string
  authorLevelText: string
  historyText: string
  overallScoreText: string
  createdAtText: string
  recommended: boolean
  recommendationText: string
  body: string
  photoUrls: string[]
}

export interface ReviewPageView {
  records: ReviewView[]
  totalGroups: number
  totalReviews: number
  page: number
  pageSize: number
  totalPages: number
}

const scoreKeys: Array<keyof ReviewScores> = ['taste', 'value', 'service', 'ambience']
const invalid = () => new Error('点评数据无法确认，请重新加载。')

function validId(value: unknown): value is string {
  return typeof value === 'string' && /^[1-9]\d*$/.test(value)
}

/** 校验关键字段并生成视图；图片地址由本站点评ID和位置生成，不信任任意外链。 */
export function toReviewPageView(raw: ReviewGroupPageRaw, expectedPage: number): ReviewPageView {
  if (
    !raw ||
    !Array.isArray(raw.records) ||
    raw.records.length > 8 ||
    raw.page !== expectedPage ||
    raw.pageSize !== 8 ||
    ![raw.totalGroups, raw.totalReviews, raw.totalPages].every(
      (value) => Number.isSafeInteger(value) && value >= 0,
    ) ||
    raw.totalReviews < raw.totalGroups ||
    raw.totalPages !== Math.ceil(raw.totalGroups / 8)
  ) {
    throw invalid()
  }

  const ids = new Set<string>()
  const records = raw.records.map((group): ReviewView => {
    const review = group.currentReview
    if (
      !group.author ||
      !validId(group.author.id) ||
      typeof group.author.username !== 'string' ||
      !review ||
      !validId(review.id) ||
      ids.has(review.id) ||
      review.scoringStatus !== 'current' ||
      typeof review.body !== 'string' ||
      typeof review.recommended !== 'boolean' ||
      !Number.isFinite(review.overallScore) ||
      !Number.isFinite(Date.parse(review.createdAt)) ||
      !review.scores ||
      !scoreKeys.every((key) => {
        const value = review.scores[key]
        return typeof value === 'number' && value >= 0 && value <= 10
      }) ||
      !Number.isSafeInteger(group.historyCount) ||
      group.historyCount < 0 ||
      !Array.isArray(review.photos) ||
      review.photos.length > 5 ||
      !review.photos.every((photo, index) => photo && photo.position === index)
    ) {
      throw invalid()
    }
    ids.add(review.id)
    const photoUrls = review.photos.map((photo, index) =>
      absoluteUrl(`/api/reviews/${encodeURIComponent(review.id)}/photos/${index}`),
    )
    return {
      id: review.id,
      authorId: group.author.id,
      username: group.author.username,
      avatarText: Array.from(group.author.username)[0] ?? '?',
      authorLevelText: group.author.level ? `Lv.${group.author.level}` : '',
      historyText: group.historyCount > 0 ? `另有 ${group.historyCount} 条历史点评` : '',
      overallScoreText: formatRating(review.overallScore),
      createdAtText: formatDate(review.createdAt),
      recommended: review.recommended,
      recommendationText: review.recommended ? '推荐' : '不推荐',
      body: review.body,
      photoUrls,
    }
  })

  return {
    records,
    totalGroups: raw.totalGroups,
    totalReviews: raw.totalReviews,
    page: raw.page,
    pageSize: raw.pageSize,
    totalPages: raw.totalPages,
  }
}
