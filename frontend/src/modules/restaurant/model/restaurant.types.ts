/** 页面只读模型，由餐厅API显式转换；示例数据仅用于独立预览与测试。 */
export interface RestaurantReadModel {
  /** 当前目录页随响应返回的正式相册封面，不再逐卡片读取点评或相册。 */
  coverPhoto?: { id: string; url: string } | null
  /** 可选的餐厅级资料：旧接口缺省为未知，不能将未填写当作支持堂食或配送。 */
  restaurantType?: 'REGULAR' | 'CANTEEN'
  supportsDineIn?: boolean | null
  supportsDelivery?: boolean | null
  /** 可选的前端校园详情扩展，旧接口缺省时保持兼容；不代表当前后端已实现。 */
  dining?: import('./campusDining').CampusDining
  id: string
  name: string
  category: string
  address: string
  tags: string[]
  latitude: number | null
  longitude: number | null
  needsLocation: boolean
  closed: boolean
  price: number | null
  priceKind: 'estimate' | 'exact' | 'unknown'
  /** 保留接口0–10分，展示时换算为五分制，排序与统计使用原值。 */
  score: number | null
  reviewCount: number
  /** 每账号每店只取最新有效点评，人数不随历史条数增长。 */
  participantCount: number
  recommendRate: number | null
  dimensions: Record<RatingDimension, number | null>
  /** 真实接口以服务端距离为准；旧快照没有此字段时才在纯逻辑测试中计算。 */
  distanceKm?: number | null
}

export type RatingDimension = 'taste' | 'value' | 'service' | 'ambience'
/** 四维评分保留详情展示，列表暂不提供按四维排序。 */
export type RestaurantSort =
  'auto' | 'relevance' | 'score' | 'reviews' | 'price' | 'distance' | 'recommend'

/** 分类使用服务端稳定编码，所有条件随分页请求提交，不在当前页二次筛选。 */
export interface CatalogFilters {
  /** GCJ-02用户位置：仅在授权成功后随查询发送，不持久化。 */
  latitude?: number
  longitude?: number
  /** 由后端在COUNT和分页前过滤，类型与分类独立，用餐方式只匹配明确支持。 */
  restaurantType?: '' | 'REGULAR' | 'CANTEEN'
  diningMode?: '' | 'dineIn' | 'delivery'
  keyword: string
  category: string
  sort: RestaurantSort
  hideClosed: boolean
  /** 人均上限（元）及距校区直线距离上限（米）；null表示不限，交由服务端分页前筛选。 */
  maxPrice: number | null
  maxDistanceMeters: number | null
}
