import { request } from '../../shared/api/request'
import { isLongId } from '../../shared/lib/id'
import type { RestaurantResponse } from './types'

/**
 * 新店投稿与资料纠错。
 *
 * 只提交待审核申请，不直接创建或覆盖公开店铺；字段范围与后端
 * `POST /api/restaurant-submissions`、`POST /api/restaurants/{id}/change-requests`
 * 的维护白名单保持一致，不接受评分、作者、审核状态或未知字段。
 */

export type RestaurantType = 'REGULAR' | 'CANTEEN'
export type SubmissionPriceKind = 'UNKNOWN' | 'EXACT' | 'ESTIMATE'
export type SubmissionBusinessStatus = 'OPERATING' | 'CLOSED'
export type TriState = boolean | null

export const MAX_SUBMISSION_PHOTOS = 5
export const MAX_SUBMISSION_NAME = 40
export const MAX_SUBMISSION_ADDRESS = 80
export const MAX_SUBMISSION_TAGS = 8
export const MAX_SUBMISSION_TAG_LENGTH = 80

export interface SubmissionDraft {
  name: string
  restaurantType: RestaurantType
  categoryCode: string
  priceKind: SubmissionPriceKind
  priceText: string
  address: string
  latitude: string
  longitude: string
  locationConfirmed: boolean
  businessStatus: SubmissionBusinessStatus
  supportsDineIn: TriState
  supportsDelivery: TriState
  tagsText: string
  photoIds: string[]
}

export interface SubmissionInput {
  clientRequestId: string
  name: string
  categoryCode: string | null
  restaurantType: RestaurantType
  priceKind: SubmissionPriceKind
  pricePerPerson: number | null
  address: string | null
  latitude: number
  longitude: number
  locationConfirmed: true
  businessStatus: SubmissionBusinessStatus
  supportsDineIn: TriState
  supportsDelivery: TriState
  tags: string[]
  photoIds: string[]
}

/** 待审修改只提交业务字段；clientRequestId 由提交入口独占，不能被修改接口复用。 */
export type SubmissionPayload = Omit<SubmissionInput, 'clientRequestId'>

/** 与后端一致的码点计数：emoji 等代理对按一个字计算。 */
export function characterCount(value: string): number {
  return Array.from(value).length
}

/** 标签按行拆分；空行是排版，不生成空标签，也不在前端去重改写用户输入。 */
export function draftTags(value: string): string[] {
  return value
    .split(/\r?\n/)
    .map((tag) => tag.trim())
    .filter(Boolean)
}

/**
 * 把公开店铺资料转成纠错草稿。
 *
 * 只带入公开可读字段，未知值保持空字符串或 null，不能用默认值补造资料。
 * 位置虽然已有历史坐标，仍要求用户在新页面重新确认；已有公开照片不进入
 * `photoIds`，纠错只提交本次新上传、审核通过后追加的图片。
 */
export function toCorrectionDraft(row: RestaurantResponse): SubmissionDraft {
  const restaurantType: RestaurantType = row.restaurantType === 'CANTEEN' ? 'CANTEEN' : 'REGULAR'
  const priceKind: SubmissionPriceKind =
    row.priceKind === 'EXACT' || row.priceKind === 'ESTIMATE' ? row.priceKind : 'UNKNOWN'
  const hasPrice = priceKind !== 'UNKNOWN' && typeof row.pricePerPerson === 'number'
  return {
    name: typeof row.name === 'string' ? row.name : '',
    restaurantType,
    // 食堂没有分类；普通餐厅按原分类带入，停用分类由服务端最终判定。
    categoryCode: restaurantType === 'CANTEEN' ? '' : (row.categoryCode ?? ''),
    priceKind,
    priceText: hasPrice ? String(row.pricePerPerson) : '',
    address: row.address ?? '',
    latitude: typeof row.latitude === 'number' ? row.latitude.toFixed(6) : '',
    longitude: typeof row.longitude === 'number' ? row.longitude.toFixed(6) : '',
    locationConfirmed: false,
    businessStatus: row.businessStatus === 'CLOSED' ? 'CLOSED' : 'OPERATING',
    supportsDineIn: row.supportsDineIn === true ? true : row.supportsDineIn === false ? false : null,
    supportsDelivery:
      row.supportsDelivery === true ? true : row.supportsDelivery === false ? false : null,
    tagsText: Array.isArray(row.tags) ? row.tags.join('\n') : '',
    photoIds: [],
  }
}

/** 坐标只接受普通十进制，不能用科学计数法或不明坐标系绕过位置确认。 */
function validCoordinate(value: string, min: number, max: number): boolean {
  const text = value.trim()
  return /^\d+(?:\.\d{1,6})?$/.test(text) && Number(text) >= min && Number(text) <= max
}

/**
 * 前端按服务端同一规则做基础校验，返回首个可展示的错误。
 *
 * 分类是否启用、图片归属和重复投稿仍由服务端最终判定；
 * 这里只避免明显无效的提交，不伪造成功。
 */
export function validateSubmissionDraft(draft: SubmissionDraft): string {
  const name = draft.name.trim()
  if (!name || characterCount(name) > MAX_SUBMISSION_NAME) {
    return `店名需为 1–${MAX_SUBMISSION_NAME} 个字符。`
  }
  if (draft.restaurantType === 'REGULAR' && !draft.categoryCode) {
    return '请选择餐厅分类。'
  }
  if (characterCount(draft.address.trim()) > MAX_SUBMISSION_ADDRESS) {
    return `地址最多 ${MAX_SUBMISSION_ADDRESS} 个字符。`
  }
  if (draft.priceKind !== 'UNKNOWN') {
    const price = draft.priceText.trim()
    if (!/^\d+$/.test(price) || Number(price) < 1 || Number(price) > 5000) {
      return '参考人均需为 1–5000 的整数元。'
    }
  }
  if (!validCoordinate(draft.latitude, 3, 54)) {
    return '纬度需为 3–54，最多 6 位小数。'
  }
  if (!validCoordinate(draft.longitude, 73, 136)) {
    return '经度需为 73–136，最多 6 位小数。'
  }
  if (!draft.locationConfirmed) {
    return '请在地图中选择并确认店铺位置。'
  }
  const tags = draftTags(draft.tagsText)
  if (
    tags.length > MAX_SUBMISSION_TAGS ||
    tags.reduce((sum, tag) => sum + characterCount(tag), 0) > MAX_SUBMISSION_TAG_LENGTH
  ) {
    return `最多 ${MAX_SUBMISSION_TAGS} 个标签，总长度最多 ${MAX_SUBMISSION_TAG_LENGTH} 个字符。`
  }
  if (
    draft.photoIds.length > MAX_SUBMISSION_PHOTOS ||
    new Set(draft.photoIds).size !== draft.photoIds.length ||
    draft.photoIds.some((id) => !isLongId(id))
  ) {
    return '照片数量或编号不符合要求。'
  }
  return ''
}

/** 始终提交完整字段；未知价格和空地址显式传 null，不能依赖服务端默认值。 */
export function buildSubmissionPayload(draft: SubmissionDraft): SubmissionPayload {
  return {
    name: draft.name.trim(),
    // 食堂不接受分类；即使草稿曾选择分类也显式提交 null。
    categoryCode: draft.restaurantType === 'CANTEEN' ? null : draft.categoryCode,
    restaurantType: draft.restaurantType,
    priceKind: draft.priceKind,
    pricePerPerson: draft.priceKind === 'UNKNOWN' ? null : Number(draft.priceText.trim()),
    address: draft.address.trim() || null,
    latitude: Number(draft.latitude),
    longitude: Number(draft.longitude),
    locationConfirmed: true,
    businessStatus: draft.businessStatus,
    supportsDineIn: draft.supportsDineIn,
    supportsDelivery: draft.supportsDelivery,
    tags: draftTags(draft.tagsText),
    photoIds: draft.photoIds,
  }
}

export interface SubmissionReceipt {
  id: string
  status: 'PENDING'
}

/**
 * 提交新店投稿。
 *
 * 成功只代表申请已受理，不代表店铺已发布；页面应引导用户到
 * “我的投稿”查看审核进度，不能把申请编号当作餐厅编号打开详情。
 */
export async function submitNewRestaurant(input: SubmissionInput): Promise<SubmissionReceipt> {
  const raw = await request<{ id: string; status: string }>({
    path: '/api/restaurant-submissions',
    method: 'POST',
    auth: 'required',
    data: input,
  })
  if (!raw || !isLongId(raw.id) || raw.status !== 'PENDING') {
    throw new Error('投稿结果无法确认，请到“我的投稿”核对待审记录。')
  }
  return { id: raw.id, status: 'PENDING' }
}

/**
 * 提交资料纠错。
 *
 * 请求体仍是完整店铺快照，服务端按目标商户生成 UPDATE 申请；
 * 成功只代表申请已受理，审核通过前不会修改公开资料。
 */
export async function submitRestaurantCorrection(
  restaurantId: string,
  input: SubmissionInput,
): Promise<SubmissionReceipt> {
  if (!isLongId(restaurantId)) throw new Error('店铺编号无法确认，请返回详情重试。')
  const raw = await request<{ id: string; status: string }>({
    path: `/api/restaurants/${encodeURIComponent(restaurantId)}/change-requests`,
    method: 'POST',
    auth: 'required',
    data: input,
  })
  if (!raw || !isLongId(raw.id) || raw.status !== 'PENDING') {
    throw new Error('纠错结果无法确认，请到“我的投稿”核对待审记录。')
  }
  return { id: raw.id, status: 'PENDING' }
}
