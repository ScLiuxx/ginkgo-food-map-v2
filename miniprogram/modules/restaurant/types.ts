import { absoluteUrl } from '../../config/env'
import {
  businessLabel,
  formatDate,
  formatDistance,
  formatPrice,
  formatRating,
  formatRecommendRate,
} from '../../shared/lib/format'

/** 后端公开餐厅字段；保留 null 语义，未知不能被转换成 0。 */
export interface RestaurantResponse {
  id: string
  name: string
  categoryCode: string | null
  categoryName: string | null
  restaurantType?: 'REGULAR' | 'CANTEEN'
  supportsDineIn?: boolean | null
  supportsDelivery?: boolean | null
  pricePerPerson: number | null
  priceKind: 'UNKNOWN' | 'EXACT' | 'ESTIMATE'
  address: string | null
  latitude: number | null
  longitude: number | null
  needsLocation: boolean
  businessStatus: 'OPERATING' | 'CLOSED'
  distanceKm: number | null
  tags: string[]
  score: number | null
  recommendRate: number | null
  reviewCount: number
  participantCount: number
  dimensions: Record<'taste' | 'value' | 'service' | 'ambience', number | null>
  coverPhoto?: { id: string; position: number; cover: boolean; url: string } | null
}

export interface RestaurantPage {
  records: RestaurantResponse[]
  total: number
  page: number
  pageSize: number
  totalPages: number
  accessiblePages?: number
}

export interface RestaurantCategory {
  code: string
  name: string
  sortOrder: number
  enabled: boolean
}

export type RestaurantSort = 'auto' | 'relevance' | 'score' | 'reviews' | 'price' | 'distance' | 'recommend'

export interface CatalogQuery {
  keyword?: string
  category?: string
  sort?: RestaurantSort
  hideClosed?: boolean
  page?: number
  pageSize?: number
}

/** 页面展示模型只包含模板需要的中文字段，WXML 中不做函数计算。 */
export interface RestaurantView {
  id: string
  name: string
  categoryLabel: string
  typeLabel: string
  scoreText: string
  reviewCountText: string
  participantCountText: string
  recommendText: string
  priceText: string
  addressText: string
  distanceText: string
  businessText: string
  supportsText: string
  closed: boolean
  tags: string[]
  coverUrl: string
  dimensions: Array<{ key: string; label: string; value: string }>
}

const dimensionLabels: Array<{ key: 'taste' | 'value' | 'service' | 'ambience'; label: string }> = [
  { key: 'taste', label: '味道' },
  { key: 'value', label: '性价比' },
  { key: 'service', label: '服务' },
  { key: 'ambience', label: '环境' },
]

/** 明确汇总用餐方式；两项都未知时返回空串，页面不展示猜测性文案。 */
function supportsText(row: RestaurantResponse): string {
  const values: string[] = []
  if (row.supportsDineIn === true) values.push('堂食')
  if (row.supportsDelivery === true) values.push('外卖')
  return values.length ? values.join(' · ') : ''
}

export function toRestaurantView(row: RestaurantResponse): RestaurantView {
  const isCanteen = row.restaurantType === 'CANTEEN'
  const distance = formatDistance(row.distanceKm)
  return {
    id: row.id,
    name: row.name,
    categoryLabel: isCanteen ? '食堂' : row.categoryName ?? '未分类',
    typeLabel: isCanteen ? '食堂' : '餐厅',
    scoreText: formatRating(row.score),
    reviewCountText: `${row.reviewCount} 条点评`,
    participantCountText: `${row.participantCount} 人评分`,
    recommendText: formatRecommendRate(row.recommendRate),
    priceText: formatPrice(row.pricePerPerson, row.priceKind),
    addressText: row.address ?? '地址待补充',
    distanceText: distance,
    businessText: businessLabel(row.businessStatus),
    supportsText: supportsText(row),
    closed: row.businessStatus === 'CLOSED',
    tags: Array.isArray(row.tags) ? row.tags : [],
    coverUrl: row.coverPhoto?.url ? absoluteUrl(row.coverPhoto.url) : '',
    dimensions: dimensionLabels.map((dimension) => ({
      key: dimension.key,
      label: dimension.label,
      value: formatRating(row.dimensions?.[dimension.key]),
    })),
  }
}

/** 本人收藏列表保留失效占位；失效项不能伪造店名和营业状态。 */
export interface FavoriteRecordRaw {
  restaurantId: string
  name: string | null
  restaurantType: 'REGULAR' | 'CANTEEN' | null
  businessStatus: 'OPERATING' | 'CLOSED' | null
  available: boolean
  createdAt: string
}

export interface FavoritePageRaw {
  userId: string
  records: FavoriteRecordRaw[]
  total: number
  page: number
  pageSize: number
  totalPages: number
}

export interface FavoriteCard {
  restaurantId: string
  name: string
  metaText: string
  createdAtText: string
  available: boolean
  closed: boolean
}

export interface FavoritePageView {
  records: FavoriteCard[]
  total: number
  page: number
  pageSize: number
  totalPages: number
}

const FAVORITE_PAGE_SIZE = 8

/**
 * 校验本人收藏分页并生成展示模型。
 *
 * 失效店铺仍可取消收藏，但服务端把名称与状态置空；
 * 这里不允许用历史资料补全，避免展示已删除店铺的误导信息。
 */
export function toFavoritePageView(raw: FavoritePageRaw, expectedPage: number): FavoritePageView {
  const invalid = () => new Error('收藏列表无法确认，请重新加载。')
  if (
    !raw ||
    !Array.isArray(raw.records) ||
    raw.records.length > FAVORITE_PAGE_SIZE ||
    raw.page !== expectedPage ||
    raw.pageSize !== FAVORITE_PAGE_SIZE ||
    !Number.isSafeInteger(raw.total) ||
    raw.total < 0 ||
    !Number.isSafeInteger(raw.totalPages) ||
    raw.totalPages !== Math.ceil(raw.total / FAVORITE_PAGE_SIZE)
  ) {
    throw invalid()
  }

  const ids = new Set<string>()
  const records = raw.records.map((row): FavoriteCard => {
    if (
      !row ||
      typeof row.restaurantId !== 'string' ||
      !/^[1-9]\d*$/.test(row.restaurantId) ||
      ids.has(row.restaurantId) ||
      typeof row.available !== 'boolean' ||
      !Number.isFinite(Date.parse(row.createdAt))
    ) {
      throw invalid()
    }
    ids.add(row.restaurantId)
    if (!row.available) {
      return {
        restaurantId: row.restaurantId,
        name: '店铺已不可用',
        metaText: '已删除或暂不可见，可移除收藏',
        createdAtText: formatDate(row.createdAt),
        available: false,
        closed: false,
      }
    }
    if (
      typeof row.name !== 'string' ||
      !row.name.trim() ||
      (row.restaurantType !== 'REGULAR' && row.restaurantType !== 'CANTEEN') ||
      (row.businessStatus !== 'OPERATING' && row.businessStatus !== 'CLOSED')
    ) {
      throw invalid()
    }
    const typeText = row.restaurantType === 'CANTEEN' ? '食堂' : '餐厅'
    return {
      restaurantId: row.restaurantId,
      name: row.name,
      metaText: `${typeText} · ${businessLabel(row.businessStatus)}`,
      createdAtText: formatDate(row.createdAt),
      available: true,
      closed: row.businessStatus === 'CLOSED',
    }
  })

  return {
    records,
    total: raw.total,
    page: raw.page,
    pageSize: raw.pageSize,
    totalPages: raw.totalPages,
  }
}
