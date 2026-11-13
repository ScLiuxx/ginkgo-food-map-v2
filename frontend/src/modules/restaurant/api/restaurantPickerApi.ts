import type { ApiResponse } from '@/shared/api/api.types'
import { httpClient } from '@/shared/api/httpClient'
import { toRestaurantModel, type RestaurantResponse } from './restaurantApi'
import type { RestaurantReadModel } from '../model/restaurant.types'

export interface PickerConditions {
  range: 'QINGSHUIHE' | 'SHAHE' | 'USER'
  distanceMeters: 1000 | 3000 | 5000
  budget: 'ANY' | '15' | '30' | '50' | '100'
  /** 五分制综合口碑分；空选项与主动选择不限分别处理。 */
  minRating: 'ANY' | '3' | '3.5' | '4'
  category: string
  diningMode: 'ANY' | 'DINE_IN' | 'DELIVERY'
  latitude?: number
  longitude?: number
}

export interface PickerBatch {
  records: RestaurantReadModel[]
  matchedCount: number
  remainingCount: number
}

/** 无状态公开查询：排除列表覆盖预取及展示记录，checkIds只复核旧候选，不重新抽取。 */
export async function fetchPickerCandidates(
  conditions: PickerConditions,
  excludeIds: string[],
  checkIds: string[] = [],
  signal?: AbortSignal,
): Promise<PickerBatch> {
  const response = await httpClient.post<
    ApiResponse<
      Omit<PickerBatch, 'records'> & {
        records: RestaurantResponse[]
      }
    >
  >('/api/restaurant-picker/candidates', { ...conditions, excludeIds, checkIds }, { signal })
  return { ...response.data.data, records: response.data.data.records.map(toRestaurantModel) }
}
