import { httpClient } from '@/shared/api/httpClient'
import type { ApiResponse } from '@/shared/api/api.types'
export interface FavoriteState {
  restaurantId: string
  favorited: boolean
}
export interface FavoriteItem {
  restaurantId: string
  name: string | null
  restaurantType: 'REGULAR' | 'CANTEEN' | null
  businessStatus: 'OPERATING' | 'CLOSED' | null
  available: boolean
  createdAt: string
}
export interface FavoritePage {
  userId: string
  records: FavoriteItem[]
  total: number
  totalPages: number
  page: number
  pageSize: number
}
export interface FavoriteVisibility {
  userId: string
  favoritesPublic: boolean
}
const options = (token: string, signal?: AbortSignal) => ({
  headers: { Authorization: `Bearer ${token}` },
  signal,
})
const invalid = () => new Error('收藏数据无法确认，请重新读取。')
/** 会话由操作开始时固定，目标由响应再次核对；未知状态不会伪装成未收藏。 */
export async function favoriteState(
  id: string,
  token: string,
  signal: AbortSignal,
): Promise<FavoriteState> {
  const response = await httpClient.get<ApiResponse<FavoriteState>>(
    `/api/restaurants/${encodeURIComponent(id)}/favorite`,
    options(token, signal),
  )
  if (
    !response.data.success ||
    response.data.data?.restaurantId !== id ||
    typeof response.data.data.favorited !== 'boolean'
  )
    throw invalid()
  return response.data.data
}
/** PUT/DELETE是目标状态而非翻转；失败由界面要求重读，避免盲目反向操作。 */
export async function setFavorite(
  id: string,
  active: boolean,
  token: string,
): Promise<FavoriteState> {
  const path = `/api/restaurants/${encodeURIComponent(id)}/favorite`
  const response = active
    ? await httpClient.put<ApiResponse<FavoriteState>>(path, undefined, options(token))
    : await httpClient.delete<ApiResponse<FavoriteState>>(path, options(token))
  const data = response.data.data
  if (!response.data.success || data?.restaurantId !== id || data.favorited !== active)
    throw invalid()
  return { restaurantId: data.restaurantId, favorited: data.favorited }
}
/** 在完整可见集合上由后端计数分页；公开列表不能接受任何失效占位。 */
export async function favoritePage(
  page: number,
  subject: string,
  own: boolean,
  token: string,
  signal: AbortSignal,
  publicPath = false,
): Promise<FavoritePage> {
  const response = await httpClient.get<ApiResponse<FavoritePage>>(
    publicPath ? `/api/users/${encodeURIComponent(subject)}/favorites` : '/api/me/favorites',
    { ...options(token, signal), params: { page, pageSize: 8 } },
  )
  const data = response.data.data
  if (
    !response.data.success ||
    !data ||
    data.userId !== subject ||
    data.page !== page ||
    data.pageSize !== 8 ||
    !Number.isSafeInteger(data.total) ||
    data.total < 0 ||
    data.totalPages !== Math.ceil(data.total / 8) ||
    !Array.isArray(data.records) ||
    data.records.length > 8 ||
    data.records.length > data.total
  )
    throw invalid()
  const ids = new Set<string>()
  const records = data.records.map((item) => {
    if (
      typeof item.restaurantId !== 'string' ||
      !/^[1-9]\d*$/.test(item.restaurantId) ||
      ids.has(item.restaurantId) ||
      typeof item.available !== 'boolean' ||
      !Number.isFinite(Date.parse(item.createdAt)) ||
      (item.available
        ? typeof item.name !== 'string' ||
          !['REGULAR', 'CANTEEN'].includes(item.restaurantType ?? '') ||
          !['OPERATING', 'CLOSED'].includes(item.businessStatus ?? '')
        : !own ||
          item.name !== null ||
          item.restaurantType !== null ||
          item.businessStatus !== null)
    )
      throw invalid()
    ids.add(item.restaurantId)
    return {
      restaurantId: item.restaurantId,
      name: item.name,
      restaurantType: item.restaurantType,
      businessStatus: item.businessStatus,
      available: item.available,
      createdAt: item.createdAt,
    }
  })
  return {
    userId: data.userId,
    records,
    page: data.page,
    pageSize: data.pageSize,
    total: data.total,
    totalPages: data.totalPages,
  }
}
/** 收藏隐私单独读取，不从主页或本地设置推断是否公开。 */
export async function favoriteVisibility(
  userId: string,
  token: string,
  signal?: AbortSignal,
  value?: boolean,
): Promise<FavoriteVisibility> {
  const path = '/api/me/favorites/visibility'
  const response =
    value === undefined
      ? await httpClient.get<ApiResponse<FavoriteVisibility>>(path, options(token, signal))
      : await httpClient.patch<ApiResponse<FavoriteVisibility>>(
          path,
          { favoritesPublic: value },
          options(token, signal),
        )
  const data = response.data.data
  if (
    !response.data.success ||
    !data ||
    data.userId !== userId ||
    typeof data.favoritesPublic !== 'boolean' ||
    (value !== undefined && data.favoritesPublic !== value)
  )
    throw invalid()
  return { userId: data.userId, favoritesPublic: data.favoritesPublic }
}
