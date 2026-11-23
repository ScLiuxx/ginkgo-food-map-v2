import type { ReviewRecommendation, Scores } from '@/modules/review/model/review'

/** 联合投稿沿用普通点评协议；作者、目标店铺与发布UUID始终由服务端确定。 */
export interface AttachedReviewInput {
  scores: Scores
  recommendation: ReviewRecommendation
  body: string
  photoIds: string[]
}

/** 页面草稿无默认推荐态度；五星控件仍使用十分制数据，避免修改历史半分值。 */
export interface AttachedReviewDraft {
  enabled: boolean
  scores: Scores
  recommendation: ReviewRecommendation | null
  body: string
}
export const attachedReviewDraft = (): AttachedReviewDraft => ({
  enabled: false,
  scores: { taste: 7, value: 7, service: 7, ambience: 7 },
  recommendation: null,
  body: '',
})
