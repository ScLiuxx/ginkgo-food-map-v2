import { httpClient } from '@/shared/api/httpClient'
import type { ApiResponse } from '@/shared/api/api.types'

/** 正式关联ID不是文件ID；地址始终由本站路径生成，不消费任意外部图片地址。 */
export interface RestaurantAlbumPhoto {
  id: string
  position: number
  cover: boolean
  url: string
}
export interface RestaurantAlbumPage {
  records: RestaurantAlbumPhoto[]
  nextCursor: string | null
}
const validId = (value: unknown): value is string =>
  typeof value === 'string' && /^[1-9][0-9]{0,18}$/u.test(value)
export function restaurantPhotoUrl(restaurantId: string, photoId: string) {
  return `/api/restaurants/${encodeURIComponent(restaurantId)}/photos/${encodeURIComponent(photoId)}`
}

/** 一次只读20张元数据；有后续游标时由用户主动加载，不遍历整本相册。 */
export async function fetchRestaurantAlbum(
  restaurantId: string,
  cursor: string | null,
  signal: AbortSignal,
): Promise<RestaurantAlbumPage> {
  const result = (
    await httpClient.get<ApiResponse<RestaurantAlbumPage>>(
      `/api/restaurants/${encodeURIComponent(restaurantId)}/photos`,
      { params: { limit: 20, cursor: cursor ?? undefined }, signal },
    )
  ).data.data
  if (
    !result ||
    !Array.isArray(result.records) ||
    (result.nextCursor !== null && !validId(result.nextCursor)) ||
    result.records.some(
      (photo) =>
        !photo ||
        !validId(photo.id) ||
        !Number.isInteger(photo.position) ||
        photo.position < 0 ||
        typeof photo.cover !== 'boolean',
    )
  )
    throw new Error('商户相册数据格式不正确')
  return {
    records: result.records.map((photo) => ({
      id: photo.id,
      position: photo.position,
      cover: photo.cover,
      url: restaurantPhotoUrl(restaurantId, photo.id),
    })),
    nextCursor: result.nextCursor,
  }
}

/** 公开JPEG匿名获取为短期Blob，既能独立重试，也能在离开页面时取消与释放图片。 */
export async function fetchRestaurantAlbumImage(
  restaurantId: string,
  photoId: string,
  signal: AbortSignal,
): Promise<Blob> {
  const result = (
    await httpClient.get<Blob>(restaurantPhotoUrl(restaurantId, photoId), {
      responseType: 'blob',
      signal,
    })
  ).data
  if (!(result instanceof Blob) || !result.size || result.type !== 'image/jpeg')
    throw new Error('商户图片文件无效')
  return result
}
