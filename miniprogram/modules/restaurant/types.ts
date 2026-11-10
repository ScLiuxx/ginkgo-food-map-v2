import { absoluteUrl } from '../../config/env'
import { businessLabel, formatDistance, formatPrice, formatRating, formatRecommendRate } from '../../shared/lib/format'

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
