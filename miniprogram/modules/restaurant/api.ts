import { request } from '../../shared/api/request'
import {
  toFavoritePageView,
  type CatalogQuery,
  type FavoritePageRaw,
  type FavoritePageView,
  type RestaurantCategory,
  type RestaurantPage,
  type RestaurantResponse,
} from './types'

/** 分类字典包含停用分类，前台只过滤 enabled；不在客户端补造分类。 */
export function fetchCategories(): Promise<RestaurantCategory[]> {
  return request<RestaurantCategory[]>({
    path: '/api/restaurant-categories',
    auth: 'none',
  })
}

/** 组装有界查询串；空字符串和 undefined 不发送，避免改变后端默认行为。 */
function catalogPath(query: CatalogQuery): string {
  const params: string[] = []
  const append = (key: string, value: string | number | boolean | undefined) => {
    if (value === undefined || value === '') return
    params.push(`${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
  }
  append('keyword', query.keyword?.trim())
  append('category', query.category)
  append('sort', query.sort)
  append('hideClosed', query.hideClosed)
  append('restaurantType', query.restaurantType)
  append('diningMode', query.diningMode)
  append('maxPrice', query.maxPrice === null || query.maxPrice === undefined ? '' : query.maxPrice)
  append('hasReviews', query.hasReviews)
  /*
   * 中心坐标与半径必须成组出现：任一缺失都不发送，
   * 避免后端把不完整条件当成“当前位置”而返回意外距离。
   */
  if (
    typeof query.latitude === 'number' &&
    typeof query.longitude === 'number' &&
    typeof query.maxDistanceMeters === 'number'
  ) {
    append('latitude', query.latitude)
    append('longitude', query.longitude)
    append('maxDistanceMeters', query.maxDistanceMeters)
  }
  append('page', query.page)
  append('pageSize', query.pageSize)
  return params.length ? `/api/restaurants?${params.join('&')}` : '/api/restaurants'
}

/** 目录是匿名读取；显式 auth:none 防止本地过期令牌影响游客浏览。 */
export function fetchRestaurants(query: CatalogQuery): Promise<RestaurantPage> {
  return request<RestaurantPage>({ path: catalogPath(query), auth: 'none' })
}

export type PickerRange = 'QINGSHUIHE' | 'SHAHE' | 'USER'
export type PickerBudget = 'ANY' | '15' | '30' | '50' | '100'
export type PickerMinRating = 'ANY' | '3' | '3.5' | '4'
export type PickerDiningMode = 'ANY' | 'DINE_IN' | 'DELIVERY'

/**
 * 服务端随机选店查询。
 *
 * checkIds 非空时进入复核模式，只返回仍符合当前条件的已知店铺；
 * excludeIds 用于排除本轮已展示与预取的店铺，服务端不保存会话。
 */
export interface PickerQuery {
  range: PickerRange
  distanceMeters: 1000 | 3000 | 5000
  budget: PickerBudget
  minRating: PickerMinRating
  category: string
  diningMode: PickerDiningMode
  latitude?: number
  longitude?: number
  excludeIds?: string[]
  checkIds?: string[]
}

export interface PickerResult {
  records: RestaurantResponse[]
  matchedCount: number
  remainingCount: number
}

/** 随机候选只允许匿名读取，失败不能被当成“没有符合条件的店铺”。 */
export function fetchPickerCandidates(query: PickerQuery): Promise<PickerResult> {
  const payload: Record<string, unknown> = {
    range: query.range,
    distanceMeters: query.distanceMeters,
    budget: query.budget,
    minRating: query.minRating,
    category: query.category,
    diningMode: query.diningMode,
    excludeIds: query.excludeIds ?? [],
    checkIds: query.checkIds ?? [],
  }
  if (query.range === 'USER') {
    payload.latitude = query.latitude
    payload.longitude = query.longitude
  }
  return request<PickerResult>({
    path: '/api/restaurant-picker/candidates',
    method: 'POST',
    auth: 'none',
    data: payload,
  })
}

export function fetchRestaurantDetail(id: string): Promise<RestaurantResponse> {
  return request<RestaurantResponse>({
    path: `/api/restaurants/${encodeURIComponent(id)}`,
    auth: 'none',
  })
}

export interface AlbumPhoto {
  id: string
  position: number
  cover: boolean
  url: string
}

export interface AlbumPage {
  records: AlbumPhoto[]
  nextCursor: string | null
}

/** 正式相册一次只读20张元数据，后续由用户主动加载，不在详情页遍历整本相册。 */
export function fetchRestaurantAlbum(id: string, cursor?: string): Promise<AlbumPage> {
  const suffix = cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''
  return request<AlbumPage>({
    path: `/api/restaurants/${encodeURIComponent(id)}/photos?limit=20${suffix}`,
    auth: 'none',
  })
}

export interface FavoriteState {
  restaurantId: string
  favorited: boolean
}

/** 收藏状态属于本人私有资料，必须携带登录令牌。 */
export function fetchFavoriteState(id: string): Promise<FavoriteState> {
  return request<FavoriteState>({
    path: `/api/restaurants/${encodeURIComponent(id)}/favorite`,
    auth: 'required',
  })
}

/** PUT/DELETE 表示明确目标状态，不使用翻转接口；失败后页面应重新读取。 */
export function setFavorite(id: string, active: boolean): Promise<FavoriteState> {
  return request<FavoriteState>({
    path: `/api/restaurants/${encodeURIComponent(id)}/favorite`,
    method: active ? 'PUT' : 'DELETE',
    auth: 'required',
  })
}

/**
 * 读取本人收藏分页。
 *
 * 只绑定 page/pageSize，不能通过参数查看他人收藏；
 * 失效店铺由服务端保留占位，页面仍可据此取消收藏。
 */
export async function fetchMyFavorites(page: number): Promise<FavoritePageView> {
  const raw = await request<FavoritePageRaw>({
    path: `/api/me/favorites?page=${page}&pageSize=8`,
    auth: 'required',
  })
  return toFavoritePageView(raw, page)
}

/**
 * 读取他人公开收藏。
 *
 * 对方关闭公开时服务端返回 403 RESTAURANT_FAVORITES_PRIVATE；
 * 页面应原样提示私密，而不是把拒绝降级成空列表。响应必须回指请求用户。
 */
export async function fetchUserFavorites(
  userId: string,
  page: number,
): Promise<FavoritePageView> {
  const raw = await request<FavoritePageRaw>({
    path: `/api/users/${encodeURIComponent(userId)}/favorites?page=${page}&pageSize=8`,
    auth: 'required',
  })
  if (!raw || raw.userId !== userId) {
    throw new Error('该用户的收藏无法确认，请重新加载。')
  }
  return toFavoritePageView(raw, page)
}
