import type { MyRequestDetail } from '../api/myRestaurantRequestsApi'

type Values = Record<string, unknown>
export interface ContributionField {
  key: string
  label: string
  text: string
  comparison: string
}
export interface ContributionChange {
  key: string
  label: string
  before: string
  after: string
}
const clean = (value: unknown) => (typeof value === 'string' ? value.trim() : '')
const numeric = (value: unknown) =>
  typeof value === 'number' && Number.isFinite(value) ? value : null
const boolean = (value: unknown) => (typeof value === 'boolean' ? value : null)
const support = (value: unknown) => (value === true ? '支持' : value === false ? '不支持' : '未知')

/**
 * 展示和修改对照共用字段口径，一行算一项；消费、用餐方式按用户看到的组合字段计数。
 * 空值统一为未知，分类按编码比较，标签按集合比较，避免服务端补充名称或重排标签造成虚假修改。
 * 坐标仅用于定位、导航和审核，不进入本人详情或修改对照的展示字段。
 */
export function contributionFields(values: Values): ContributionField[] {
  const restaurantType = clean(values.restaurantType)
  const category =
    restaurantType === 'CANTEEN' ? '' : clean(values.categoryCode) || clean(values.categoryName)
  const typeName =
    restaurantType === 'CANTEEN' ? '食堂' : restaurantType === 'REGULAR' ? '普通餐厅' : '未记录'
  const categoryName = clean(values.categoryName) || clean(values.categoryCode) || '未填写'
  const price = numeric(values.pricePerPerson)
  const knownPrice = values.priceKind !== 'UNKNOWN' && price !== null
  const tags = Array.isArray(values.tags)
    ? [...new Set(values.tags.map(clean).filter(Boolean))].sort()
    : []
  const row = (
    key: string,
    label: string,
    text: string,
    comparison: unknown,
  ): ContributionField => ({ key, label, text, comparison: JSON.stringify(comparison) })
  return [
    row('name', '店铺名称', clean(values.name) || '未填写', clean(values.name)),
    row(
      'category',
      '类型 / 分类',
      restaurantType === 'CANTEEN' ? typeName : `${typeName} · ${categoryName}`,
      [restaurantType, category],
    ),
    row('price', '人均消费', knownPrice ? `¥${price} / 人` : '未知', knownPrice ? price : null),
    row('address', '店铺地址', clean(values.address) || '未填写', clean(values.address)),
    row(
      'businessStatus',
      '营业状态',
      values.businessStatus === 'OPERATING'
        ? '营业中'
        : values.businessStatus === 'CLOSED'
          ? '已停业'
          : '未知',
      ['OPERATING', 'CLOSED'].includes(String(values.businessStatus)) ? values.businessStatus : '',
    ),
    row(
      'dining',
      '堂食 / 外卖',
      `${support(values.supportsDineIn)} / ${support(values.supportsDelivery)}`,
      [boolean(values.supportsDineIn), boolean(values.supportsDelivery)],
    ),
    row('tags', '标签', tags.join('、') || '未填写', tags),
  ]
}

/** 只有普通审核通过才比较原投稿和采用的内容；已收录关联的数据不同不代表管理员修改。 */
export function contributionChanges(
  detail: Pick<MyRequestDetail, 'application' | 'proposed' | 'approved'>,
): ContributionChange[] {
  if (
    detail.application.status !== 'APPROVED' ||
    detail.application.approvalMode === 'LINKED_EXISTING' ||
    !detail.approved
  )
    return []
  const before = contributionFields(detail.proposed)
  return contributionFields(detail.approved).flatMap((field, index) => {
    const original = before[index]!
    return field.comparison === original.comparison
      ? []
      : [{ key: field.key, label: field.label, before: original.text, after: field.text }]
  })
}
