import { httpClient } from '@/shared/api/httpClient'
import type { ApiResponse } from '@/shared/api/api.types'

export interface RestaurantPhotoSelection {
  photoId?: string
  fileId?: string
}
export interface ReviewPhotoCandidate {
  reviewId: string
  position: number
  url: string
  username: string
  body: string
  createdAt: string
}
export interface ReviewPhotoCandidates {
  records: ReviewPhotoCandidate[]
  hasMore: boolean
}

export async function fetchReviewPhotoCandidates(
  id: string,
  page: number,
  token: string,
  signal: AbortSignal,
): Promise<ReviewPhotoCandidates> {
  const response = await httpClient.get<ApiResponse<ReviewPhotoCandidates>>(
    `/api/admin/restaurants/${encodeURIComponent(id)}/review-photos`,
    {
      params: { page },
      headers: { Authorization: `Bearer ${token}` },
      signal,
    },
  )
  const data = response.data.data
  if (
    !response.data.success ||
    !data ||
    typeof data.hasMore !== 'boolean' ||
    !Array.isArray(data.records) ||
    data.records.length > 12 ||
    data.records.some(
      (p) =>
        !/^[1-9]\d{0,18}$/.test(p.reviewId) ||
        !Number.isInteger(p.position) ||
        p.position < 0 ||
        p.position > 4 ||
        p.url !== `/api/reviews/${p.reviewId}/photos/${p.position}` ||
        typeof p.username !== 'string' ||
        typeof p.body !== 'string' ||
        !Number.isFinite(Date.parse(p.createdAt)),
    )
  )
    throw new Error('点评照片无法确认，请重新读取。')
  return data
}

/** 复制JPEG再以独立RESTAURANT临时文件上传，不复用原点评绑定或文件ID。 */
export async function readReviewPhoto(
  photo: ReviewPhotoCandidate,
  signal: AbortSignal,
): Promise<Blob> {
  const response = await httpClient.get<Blob>(photo.url, { responseType: 'blob', signal })
  if (
    !(response.data instanceof Blob) ||
    response.data.type !== 'image/jpeg' ||
    !response.data.size
  )
    throw new Error('点评图片读取失败，请重试。')
  return response.data
}
