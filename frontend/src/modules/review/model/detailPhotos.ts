import type { ReviewItem } from './review'

/** 只依据已有明确示例标识提示来源，不认证真实到店、不改写原始正文或评分。 */
export function hasExampleMarker(body: string): boolean {
  return /联调模拟|测试数据|示例点评|模拟点评/.test(body)
}

export interface DetailReviewPhoto {
  id: string
  url: string
  username: string
  reviewId: string
  example: boolean
}

export interface DetailPhotoUpdate {
  restaurantId: string
  state: 'loading' | 'ready' | 'error'
  photos: DetailReviewPhoto[]
}

/** 仅摘取本次读取页的点评图片，保留作者与原点评归属；不声称是全部照片或商家官方图。 */
export function detailPhotos(records: ReviewItem[]): DetailReviewPhoto[] {
  return [
    ...new Map(
      records
        .flatMap((review) =>
          review.photos.map((photo) => ({
            ...photo,
            username: review.username,
            reviewId: review.id,
            example: hasExampleMarker(review.body),
          })),
        )
        .map((photo) => [photo.id, photo]),
    ).values(),
  ]
}
