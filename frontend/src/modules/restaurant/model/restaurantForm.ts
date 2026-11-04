import type { RestaurantResponse, RestaurantSaveInput } from '../api/restaurantApi'

/** 数值先按文字保留，避免空输入被Number转换为0或小数被输入控件悄悄截断。 */
export interface RestaurantDraft {
  name: string
  categoryCode: string
  priceKind: RestaurantResponse['priceKind']
  price: string
  address: string
  latitude: string
  longitude: string
  locationConfirmed: boolean
  businessStatus: RestaurantResponse['businessStatus']
  tags: string
}

export type RestaurantField = keyof RestaurantDraft
export type RestaurantErrors = Partial<Record<RestaurantField, string>>

/** 新增不填默认位置；编辑保持服务端全量资料，但要求本次重新确认位置。 */
export function restaurantDraft(value?: RestaurantResponse): RestaurantDraft {
  return {
    name: value?.name ?? '',
    categoryCode: value?.categoryCode ?? '',
    priceKind: value?.priceKind ?? 'UNKNOWN',
    price: value?.pricePerPerson?.toString() ?? '',
    address: value?.address ?? '',
    latitude: value?.latitude?.toString() ?? '',
    longitude: value?.longitude?.toString() ?? '',
    locationConfirmed: false,
    businessStatus: value?.businessStatus ?? 'OPERATING',
    tags: value?.tags.join('\n') ?? '',
  }
}

/** 按Unicode码点计数，与服务端约束一致；空行是排版，不生成空标签。 */
export const characterCount = (value: string): number => Array.from(value).length
export const draftTags = (value: string): string[] =>
  value
    .split(/\r?\n/)
    .map((v) => v.trim())
    .filter(Boolean)

/** 坐标只接受普通十进制，不能用科学计数法或不明坐标系绕过位置确认。 */
function coordinate(value: string, min: number, max: number): boolean {
  const text = value.trim()
  return /^\d+(?:\.\d{1,6})?$/.test(text) && Number(text) >= min && Number(text) <= max
}

/** 客户端给出可定位的输入反馈；分类启用及标签同库去重仍由服务端最终校验。 */
export function validateRestaurantDraft(
  form: RestaurantDraft,
  type: 'REGULAR' | 'CANTEEN' = 'REGULAR',
): RestaurantErrors {
  const errors: RestaurantErrors = {}
  if (!form.name.trim() || characterCount(form.name.trim()) > 40)
    errors.name = '名称需为1–40个字符。'
  if (type === 'REGULAR' && !form.categoryCode) errors.categoryCode = '请选择餐厅分类。'
  if (characterCount(form.address.trim()) > 80) errors.address = '地址最多80个字符。'
  if (
    form.priceKind !== 'UNKNOWN' &&
    (!/^\d+$/.test(form.price.trim()) || Number(form.price) < 1 || Number(form.price) > 5000)
  ) {
    errors.price = '人均需为1–5000的整数元。'
  }
  if (!coordinate(form.latitude, 3, 54)) errors.latitude = '纬度需为3–54，最多6位小数。'
  if (!coordinate(form.longitude, 73, 136)) errors.longitude = '经度需为73–136，最多6位小数。'
  if (!form.locationConfirmed) errors.locationConfirmed = '请核对坐标后确认餐厅位置。'
  const tags = draftTags(form.tags)
  if (tags.length > 8 || tags.reduce((sum, tag) => sum + characterCount(tag), 0) > 80) {
    errors.tags = '最多8个标签，输入总字符数最多80。'
  }
  return errors
}

/** 始终构造完整替换请求；未知价格/空地址明确为null，不能把省略误当成保留。 */
export function restaurantInput(
  form: RestaurantDraft,
  type: 'REGULAR' | 'CANTEEN' = 'REGULAR',
): RestaurantSaveInput {
  return {
    name: form.name.trim(),
    // 即使草稿曾选择普通餐厅分类，食堂请求也始终显式使用null。
    categoryCode: type === 'CANTEEN' ? null : form.categoryCode,
    priceKind: form.priceKind,
    pricePerPerson: form.priceKind === 'UNKNOWN' ? null : Number(form.price),
    address: form.address.trim() || null,
    latitude: Number(form.latitude),
    longitude: Number(form.longitude),
    locationConfirmed: true,
    businessStatus: form.businessStatus,
    tags: draftTags(form.tags),
  }
}
