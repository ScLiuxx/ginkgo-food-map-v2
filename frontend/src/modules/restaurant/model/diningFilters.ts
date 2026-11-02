import type { CatalogFilters, RestaurantReadModel } from './restaurant.types'

/** 类型优先采用明确资料，旧数据兼容正式“食堂”分类；用餐方式只接受明确的true，不从名称或标签猜测。 */
export function matchesDiningFilters(
  record: RestaurantReadModel,
  filters: CatalogFilters,
): boolean {
  if (record.dining?.kind === 'window') return false
  const type =
    record.restaurantType ??
    (record.dining?.kind === 'canteen' || record.category === '食堂' ? 'CANTEEN' : 'REGULAR')
  if (filters.restaurantType && type !== filters.restaurantType) return false
  if (filters.diningMode === 'dineIn' && record.supportsDineIn !== true) return false
  if (filters.diningMode === 'delivery' && record.supportsDelivery !== true) return false
  return true
}
