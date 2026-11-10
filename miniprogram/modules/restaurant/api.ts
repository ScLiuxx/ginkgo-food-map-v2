import { request } from '../../shared/api/request'
import type {
  CatalogQuery,
  RestaurantCategory,
  RestaurantPage,
  RestaurantResponse,
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
  append('page', query.page)
  append('pageSize', query.pageSize)
  return params.length ? `/api/restaurants?${params.join('&')}` : '/api/restaurants'
}

/** 目录是匿名读取；显式 auth:none 防止本地过期令牌影响游客浏览。 */
export function fetchRestaurants(query: CatalogQuery): Promise<RestaurantPage> {
  return request<RestaurantPage>({ path: catalogPath(query), auth: 'none' })
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

/** 食堂窗口公开集合；窗口详情尚未在小程序第一批接入，仅展示窗口摘要。 */
export interface CanteenWindowResponse {
  id: string
  canteenId: string
  canteenName: string
  name: string
  floorNumber: number
  businessStatus: 'OPERATING' | 'CLOSED'
  priceKind: 'UNKNOWN' | 'EXACT' | 'ESTIMATE'
  pricePerPerson: number | null
  score: number | null
  reviewCount: number
  coverUrl?: string | null
}

export function fetchCanteenWindows(canteenId: string): Promise<CanteenWindowResponse[]> {
  return request<CanteenWindowResponse[]>({
    path: `/api/restaurants/${encodeURIComponent(canteenId)}/windows`,
    auth: 'none',
  })
}
