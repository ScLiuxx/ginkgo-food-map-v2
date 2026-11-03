import type { ApiResponse } from '@/shared/api/api.types'
import { httpClient } from '@/shared/api/httpClient'
import type { CatalogFilters, RestaurantReadModel } from '../model/restaurant.types'
import { matchesDiningFilters } from '../model/diningFilters'
import { restaurantPhotoUrl, type RestaurantAlbumPhoto } from './restaurantAlbumApi'

/** API字段独立于页面模型，保留字符串ID和所有空值，不将未知价格或评分转换为0。 */
export interface RestaurantResponse {
  coverPhoto?: RestaurantAlbumPhoto | null
  restaurantType?: RestaurantReadModel['restaurantType']
  supportsDineIn?: boolean | null
  supportsDelivery?: boolean | null
  /** 前端预留的校园详情契约，当前后端没有该字段；不发起不存在的窗口/菜品接口。 */
  dining?: RestaurantReadModel['dining']
  id: string
  name: string
  categoryCode: string
  categoryName: string
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
  dimensions: RestaurantReadModel['dimensions']
}

export interface RestaurantCategory {
  code: string
  name: string
  sortOrder: number
  enabled: boolean
}

/** 管理快照仅在服务端授权后读取；保留完整编辑字段，不从公开页面猜测归属。 */
export interface RestaurantManagement {
  restaurant: RestaurantResponse
  deleted: boolean
}

/** PUT为完整替换，显式提交全部可编辑字段；身份、来源、评分及版本均不属于输入。 */
export interface RestaurantSaveInput {
  name: string
  categoryCode: string
  priceKind: RestaurantResponse['priceKind']
  pricePerPerson: number | null
  address: string | null
  latitude: number
  longitude: number
  locationConfirmed: true
  businessStatus: RestaurantResponse['businessStatus']
  tags: string[]
}

export interface RestaurantPage<T = RestaurantReadModel> {
  page: number
  pageSize: number
  total: number
  totalPages: number
  records: T[]
}

/** 枚举在边界转换，页面不依赖数据库列名；地址空值仅在显示层转为空串。 */
export function toRestaurantModel(value: RestaurantResponse): RestaurantReadModel {
  return {
    coverPhoto: value.coverPhoto
      ? { id: value.coverPhoto.id, url: restaurantPhotoUrl(value.id, value.coverPhoto.id) }
      : null,
    restaurantType: value.restaurantType,
    supportsDineIn: value.supportsDineIn ?? null,
    supportsDelivery: value.supportsDelivery ?? null,
    ...(value.dining ? { dining: value.dining } : {}),
    id: value.id,
    name: value.name,
    category: value.categoryName,
    address: value.address ?? '',
    tags: value.tags,
    latitude: value.latitude,
    longitude: value.longitude,
    needsLocation: value.needsLocation,
    closed: value.businessStatus === 'CLOSED',
    price: value.pricePerPerson,
    priceKind: { UNKNOWN: 'unknown', EXACT: 'exact', ESTIMATE: 'estimate' }[
      value.priceKind
    ] as RestaurantReadModel['priceKind'],
    score: value.score,
    recommendRate: value.recommendRate,
    reviewCount: value.reviewCount,
    participantCount: value.participantCount,
    dimensions: value.dimensions,
    distanceKm: value.distanceKm,
  }
}

/** 字典包含停用历史分类，仍允许浏览；不在前端补造分类。 */
export async function fetchRestaurantCategories(
  signal?: AbortSignal,
): Promise<RestaurantCategory[]> {
  const response = await httpClient.get<ApiResponse<RestaurantCategory[]>>(
    '/api/restaurant-categories',
    { signal },
  )
  return response.data.data
}

/** 全量条件交给服务端；显式列出有效字段，不发送已经移除的杯型和推荐/踩雷参数。 */
export async function fetchRestaurants(
  filters: CatalogFilters,
  page: number,
  pageSize: number,
  signal?: AbortSignal,
  bounds?: { west: number; south: number; east: number; north: number },
): Promise<RestaurantPage> {
  // 新条件尚无服务端协议，仅选中时遍历旧接口完整分页后过滤、计数、再分页。
  // 共用取消信号；任一批次失败均抛错，不用不完整结果冒充全部商家。未来由服务端取代此过渡逻辑。
  if (filters.restaurantType || filters.diningMode) {
    const original = { ...filters, restaurantType: '' as const, diningMode: '' as const }
    const records: RestaurantReadModel[] = []
    for (let batch = 1; ; batch++) {
      const result = await fetchRestaurants(original, batch, 100, signal, bounds)
      records.push(...result.records.filter((record) => matchesDiningFilters(record, filters)))
      if (batch >= result.totalPages) break
    }
    return {
      page,
      pageSize,
      total: records.length,
      totalPages: Math.ceil(records.length / pageSize),
      records: records.slice((page - 1) * pageSize, page * pageSize),
    }
  }
  const response = await httpClient.get<ApiResponse<RestaurantPage<RestaurantResponse>>>(
    '/api/restaurants',
    {
      params: {
        ...bounds,
        latitude: filters.latitude,
        longitude: filters.longitude,
        keyword: filters.keyword,
        category: filters.category,
        sort: filters.sort,
        hideClosed: filters.hideClosed,
        maxPrice: filters.maxPrice ?? undefined,
        maxDistanceMeters: filters.maxDistanceMeters ?? undefined,
        page,
        pageSize,
      },
      paramsSerializer: { indexes: null },
      signal,
    },
  )
  return { ...response.data.data, records: response.data.data.records.map(toRestaurantModel) }
}

/** 详情独立读取，刷新或直接打开链接无需先加载目录。 */
export async function fetchRestaurant(
  id: string,
  signal?: AbortSignal,
): Promise<RestaurantReadModel> {
  const response = await httpClient.get<ApiResponse<RestaurantResponse>>(
    `/api/restaurants/${encodeURIComponent(id)}`,
    { signal },
  )
  return toRestaurantModel(response.data.data)
}

/** 固定发起操作时的凭证，避免请求排队期间切换账号后用新身份提交旧表单。 */
function authenticated(token: string) {
  return { headers: { Authorization: `Bearer ${token}` } }
}

/** 管理接口的成功结果代表本次服务端授权，客户端不自行推断创建者身份。 */
export async function fetchRestaurantManagement(
  id: string,
  token: string,
  signal?: AbortSignal,
): Promise<RestaurantManagement> {
  const response = await httpClient.get<ApiResponse<RestaurantManagement>>(
    `/api/restaurants/${encodeURIComponent(id)}/management`,
    { ...authenticated(token), signal },
  )
  return response.data.data
}

/** 新建不会自动重试；超时可能已提交，调用方应先核对目录。 */
export async function createRestaurant(input: RestaurantSaveInput, token: string) {
  const response = await httpClient.post<ApiResponse<RestaurantManagement>>(
    '/api/restaurants',
    input,
    authenticated(token),
  )
  return response.data.data
}

/** 整份保存及标签替换由后端事务完成，不附加版本号或客户端身份字段。 */
export async function updateRestaurant(id: string, input: RestaurantSaveInput, token: string) {
  const response = await httpClient.put<ApiResponse<RestaurantManagement>>(
    `/api/restaurants/${encodeURIComponent(id)}`,
    input,
    authenticated(token),
  )
  return response.data.data
}

/** 软删除无请求体和版本参数；只有收到成功响应才从页面移除。 */
export async function deleteRestaurant(id: string, token: string) {
  const response = await httpClient.delete<ApiResponse<RestaurantManagement>>(
    `/api/restaurants/${encodeURIComponent(id)}`,
    authenticated(token),
  )
  return response.data.data
}

/** 纠错读取公开资料，允许登录用户为任意公开商户提出申请，不获取归属管理权限。 */
export async function fetchCorrectionSource(id: string, signal?: AbortSignal) {
  const response = await httpClient.get<ApiResponse<RestaurantResponse>>(
    `/api/restaurants/${encodeURIComponent(id)}`,
    { signal },
  )
  return response.data.data
}

export interface RestaurantCorrectionInput extends RestaurantSaveInput {
  photoIds?: string[]
  clientRequestId: string
  restaurantType: 'REGULAR' | 'CANTEEN'
  supportsDineIn: boolean | null
  supportsDelivery: boolean | null
}

/** 仅创建待审核申请；固定发起时的账号，失败不自动重试，保留请求ID供手动重试。 */
export async function submitRestaurantCorrection(
  id: string,
  input: RestaurantCorrectionInput,
  token: string,
) {
  const response = await httpClient.post<ApiResponse<{ id: string; status: 'PENDING' }>>(
    `/api/restaurants/${encodeURIComponent(id)}/change-requests`,
    input,
    authenticated(token),
  )
  return response.data.data
}

/** 新店投稿只返回待审申请编号，不能当作正式餐厅ID打开详情。 */
export async function submitNewRestaurant(input: RestaurantCorrectionInput, token: string) {
  const response = await httpClient.post<ApiResponse<{ id: string; status: 'PENDING' }>>(
    '/api/restaurant-submissions',
    input,
    authenticated(token),
  )
  return response.data.data
}
