import type { InjectionKey } from 'vue'
import type { RatingDimension } from '@/modules/restaurant/model/restaurant.types'
export type ReviewSort = 'DEFAULT' | 'TIME'
export type Scores = Record<RatingDimension, number>
/** 与后端发布校验及数据库位置范围同步。 */
export const MAX_REVIEW_PHOTOS = 5
export interface ReviewPhoto {
  id: string
  url: string
}
export interface ReviewItem {
  id: string
  authorId?: string
  authorLevel?: number
  historyCount?: number
  nextHistoryCursor?: string | null
  /** 正式读取使用服务端单条总分；预览仍可从四维计算。 */
  overallScore?: number
  /** 计分归属必须由完整数据的服务端确定，不能按当前分页或同名账号推断。 */
  scoringStatus?: 'current' | 'history'
  /** 同人同店的历史记录由完整数据源归组；真实服务分页须显式返回，不在客户端猜测。 */
  previousReviews?: ReviewItem[]
  username: string
  createdAt: string
  scores: Scores
  recommended: boolean
  body: string
  photos: ReviewPhoto[]
  /** 独立预览的演示开关；正式入口比较已认证账号ID与作者ID，授权仍由后端完成。 */
  canDelete: boolean
  replies: { id: string; username: string; body: string; createdAt: string }[]
}
export interface ReviewDraft {
  restaurantId: string
  scores: Scores
  recommended: boolean
  body: string
  photos: File[]
  /** 正式传输使用已上传文件ID；预览传输仍可以使用本地File。 */
  photoIds?: string[]
  clientRequestId: string
}
export interface ReviewPublishReceipt {
  reviewId: string
  restaurantId: string
  replayed: boolean
}
/** 单层回复独立分页，始终归属于请求路径中的具体点评。 */
export interface ReplyPage {
  records: {
    id: string
    author: { id: string; username: string }
    body: string
    createdAt: string
  }[]
  hasMore: boolean
  nextCursor: string | null
}
export interface ReplyReceipt {
  replyId: string
  reviewId: string
  replayed: boolean
}
/** 分页单位为作者组，人数与全部点评条数分开显示。 */
export interface ReviewPage {
  records: ReviewItem[]
  totalGroups: number
  totalReviews: number
  page: number
  pageSize: number
  totalPages: number
}
/** 历史游标只向旧记录移动，当前代表变化时由界面刷新主列表。 */
export interface ReviewHistoryPage {
  records: ReviewItem[]
  currentReviewId: string | null
  hasMore: boolean
  nextCursor: string | null
}
/** 发布已开放，读取/管理仍独立标明状态；示例传输仅注入独立预览子树。 */
export interface ReviewGateway {
  supportsPhotos?: boolean
  supportsReplies?: boolean
  uploadPhoto?(file: File, token: string): Promise<string>
  cancelPhoto?(fileId: string, token: string): Promise<void>
  list(
    restaurantId: string,
    signal: AbortSignal,
    page?: number,
    sort?: ReviewSort,
  ): Promise<ReviewPage>
  history?(
    restaurantId: string,
    authorId: string,
    signal: AbortSignal,
    cursor?: string,
  ): Promise<ReviewHistoryPage>
  publish(draft: ReviewDraft): Promise<ReviewPublishReceipt | void>
  listReplies?(reviewId: string, signal: AbortSignal, cursor?: string): Promise<ReplyPage>
  reply(
    reviewId: string,
    body: string,
    requestId: string,
    signal?: AbortSignal,
  ): Promise<ReplyReceipt | void>
  remove(reviewId: string): Promise<void>
}
export const reviewGatewayKey: InjectionKey<ReviewGateway> = Symbol('review-gateway')
const unavailable = async (): Promise<never> => {
  throw new Error('评价服务尚未开放，当前内容未发布。')
}
export const unavailableReviewGateway: ReviewGateway = {
  list: unavailable,
  publish: unavailable,
  reply: unavailable,
  remove: unavailable,
}
/** 半分先转整数单位计算，避免7.875的二进制浮点舍入偏差。 */
export function overall(scores: Scores): number {
  return Math.round(Object.values(scores).reduce((a, b) => a + b * 2, 0) * 12.5) / 100
}
export function validBody(body: string, min: number, max: number): boolean {
  const n = Array.from(body.trim()).length
  return n >= min && n <= max
}
