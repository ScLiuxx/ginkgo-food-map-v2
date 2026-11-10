import { request } from '../../shared/api/request'
import { absoluteUrl } from '../../config/env'
import {
  businessLabel,
  formatPrice,
  formatRating,
  formatRecommendRate,
} from '../../shared/lib/format'
import {
  toFavoritePageView,
  type CatalogQuery,
  type FavoritePageRaw,
  type FavoritePageView,
  type RestaurantCategory,
  type RestaurantPage,
  type RestaurantResponse,
  type RestaurantView,
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

export interface CanteenWindowDetailView {
  view: RestaurantView
  description: string
}

interface CanteenWindowDetailRaw {
  id: string
  canteenId: string
  canteenName: string
  name: string
  floorNumber: number
  description: string | null
  priceKind: 'UNKNOWN' | 'EXACT' | 'ESTIMATE'
  pricePerPerson: number | null
  businessStatus: 'OPERATING' | 'CLOSED'
  score: number | null
  taste: number | null
  value: number | null
  service: number | null
  ambience: number | null
  recommendRate: number | null
  reviewCount: number
  participantCount: number
  address: string | null
}

function isId(value: unknown): value is string {
  return typeof value === 'string' && /^[1-9]\d*$/.test(value)
}

function inRange(value: unknown, min: number, max: number): boolean {
  return typeof value === 'number' && Number.isFinite(value) && value >= min && value <= max
}

/**
 * 读取独立窗口详情。
 *
 * 窗口评分、四维与推荐率由父食堂和窗口统计提供；推荐率在窗口接口是 0–100 的百分数，
 * 与餐厅目录的 0–1 比例不同，这里统一换算为比例后再展示，不能直接套用餐厅口径。
 * 响应必须回指请求的窗口编号，避免错误路由把其他窗口资料显示在当前页。
 */
export async function fetchCanteenWindowDetail(
  windowId: string,
): Promise<CanteenWindowDetailView> {
  const row = await request<CanteenWindowDetailRaw>({
    path: `/api/canteen-windows/${encodeURIComponent(windowId)}`,
    auth: 'none',
  })
  if (
    !row ||
    row.id !== windowId ||
    !isId(row.canteenId) ||
    typeof row.canteenName !== 'string' ||
    !row.canteenName.trim() ||
    typeof row.name !== 'string' ||
    !row.name.trim() ||
    !Number.isSafeInteger(row.floorNumber) ||
    row.floorNumber < -10 ||
    row.floorNumber > 100 ||
    !(row.description === null || typeof row.description === 'string') ||
    (row.priceKind !== 'UNKNOWN' && row.priceKind !== 'EXACT' && row.priceKind !== 'ESTIMATE') ||
    !(
      row.pricePerPerson === null ||
      (typeof row.pricePerPerson === 'number' &&
        Number.isSafeInteger(row.pricePerPerson) &&
        row.pricePerPerson >= 1 &&
        row.pricePerPerson <= 5000)
    ) ||
    (row.businessStatus !== 'OPERATING' && row.businessStatus !== 'CLOSED') ||
    !(
      row.recommendRate === null ||
      (typeof row.recommendRate === 'number' &&
        Number.isFinite(row.recommendRate) &&
        row.recommendRate >= 0 &&
        row.recommendRate <= 100)
    ) ||
    !Number.isSafeInteger(row.reviewCount) ||
    row.reviewCount < 0 ||
    !Number.isSafeInteger(row.participantCount) ||
    row.participantCount < 0 ||
    row.participantCount > row.reviewCount ||
    !(row.address === null || typeof row.address === 'string')
  ) {
    throw new Error('窗口资料无法确认，请重新加载。')
  }
  const dimensionsOk = [row.score, row.taste, row.value, row.service, row.ambience].every(
    (value) => value === null || inRange(value, 0, 10),
  )
  if (!dimensionsOk) {
    throw new Error('窗口资料无法确认，请重新加载。')
  }

  const rate = row.recommendRate === null ? null : row.recommendRate / 100
  return {
    description: row.description?.trim() ?? '',
    view: {
      id: row.id,
      name: row.name,
      categoryLabel: `${row.canteenName} · ${row.floorNumber} 层`,
      typeLabel: '食堂窗口',
      scoreText: formatRating(row.score),
      reviewCountText: `${row.reviewCount} 条点评`,
      participantCountText: `${row.participantCount} 人评分`,
      recommendText: formatRecommendRate(rate),
      priceText: formatPrice(row.pricePerPerson, row.priceKind),
      addressText: row.address?.trim() || '地址待补充',
      distanceText: '',
      businessText: businessLabel(row.businessStatus),
      supportsText: '',
      closed: row.businessStatus === 'CLOSED',
      tags: [],
      coverUrl: '',
      dimensions: [
        { key: 'taste', label: '味道', value: formatRating(row.taste) },
        { key: 'value', label: '性价比', value: formatRating(row.value) },
        { key: 'service', label: '服务', value: formatRating(row.service) },
        { key: 'ambience', label: '环境', value: formatRating(row.ambience) },
      ],
    },
  }
}

export interface CanteenWindowPhoto {
  id: string
  url: string
}

interface CanteenWindowPhotoRaw {
  id: string
  cover: boolean
}

/**
 * 读取窗口相册。
 *
 * 窗口相册最多 5 张且没有分页游标；图片地址由窗口编号和文件编号重新生成，
 * 不直接信任响应中的路径，避免跨域或错误资源被当作窗口照片。
 */
export async function fetchCanteenWindowPhotos(windowId: string): Promise<CanteenWindowPhoto[]> {
  const rows = await request<CanteenWindowPhotoRaw[]>({
    path: `/api/canteen-windows/${encodeURIComponent(windowId)}/photos`,
    auth: 'none',
  })
  if (!Array.isArray(rows) || rows.length > 5) {
    throw new Error('窗口相册无法确认，请重新加载。')
  }
  const seen = new Set<string>()
  return rows.map((row) => {
    if (!row || !isId(row.id) || seen.has(row.id) || typeof row.cover !== 'boolean') {
      throw new Error('窗口相册无法确认，请重新加载。')
    }
    seen.add(row.id)
    return {
      id: row.id,
      url: absoluteUrl(
        `/api/canteen-windows/${encodeURIComponent(windowId)}/photos/${encodeURIComponent(row.id)}`,
      ),
    }
  })
}
