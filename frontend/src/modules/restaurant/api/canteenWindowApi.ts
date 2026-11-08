import { httpClient } from '@/shared/api/httpClient'
import type { ApiResponse } from '@/shared/api/api.types'
import type { RestaurantReadModel } from '../model/restaurant.types'
import type { DiningWindow } from '../model/campusDining'

/** 窗口拥有独立身份，所有请求显式使用窗口接口。 */
export interface CanteenWindow {
  id: string
  canteenId: string
  canteenName: string
  campusCode: string | null
  name: string
  floorNumber: number

  description: string | null
  coverUrl?: string | null
  priceKind: 'UNKNOWN' | 'EXACT' | 'ESTIMATE'
  pricePerPerson: number | null
  businessStatus: 'OPERATING' | 'CLOSED'
  deleted: boolean
  score: number | null
  taste: number | null
  value: number | null
  service: number | null
  ambience: number | null
  recommendRate: number | null
  reviewCount: number
  participantCount: number
  address: string | null
  latitude: number | null
  longitude: number | null
  needsLocation: boolean
}
export type WindowInput = Pick<
  CanteenWindow,
  'name' | 'floorNumber' | 'description' | 'priceKind' | 'pricePerPerson' | 'businessStatus'
> & { clientRequestId?: string }
export function toDiningWindow(row: CanteenWindow): DiningWindow {
  return {
    id: row.id,
    canteenId: row.canteenId,
    name: row.name,
    floor: `${row.floorNumber}层`,
    description: row.description,
    imageUrl: row.coverUrl ?? undefined,
    score: row.score,
    reviewCount: row.reviewCount,
    price: row.pricePerPerson,
    closed: row.businessStatus === 'CLOSED',
    specialties: [],
    dishes: [],
  }
}
/** 完整公开集合包括停业窗口，删除窗口仅出现在管理员列表。 */
export async function fetchCanteenWindows(
  id: string,
  signal?: AbortSignal,
): Promise<DiningWindow[]> {
  const response = await httpClient.get<ApiResponse<CanteenWindow[]>>(
    `/api/restaurants/${encodeURIComponent(id)}/windows`,
    { signal },
  )
  return response.data.data.map(toDiningWindow)
}
export async function fetchWindow(id: string, signal?: AbortSignal): Promise<RestaurantReadModel> {
  const response = await httpClient.get<ApiResponse<CanteenWindow>>(
    `/api/canteen-windows/${encodeURIComponent(id)}`,
    { signal },
  )
  const row = response.data.data
  return {
    id: row.id,
    name: row.name,
    category: '',
    address: row.address ?? '',
    latitude: row.latitude,
    longitude: row.longitude,
    needsLocation: row.needsLocation,
    closed: row.businessStatus === 'CLOSED',
    price: row.pricePerPerson,
    priceKind: ({ UNKNOWN: 'unknown', EXACT: 'exact', ESTIMATE: 'estimate' } as const)[
      row.priceKind
    ],
    score: row.score,
    recommendRate: row.recommendRate,
    reviewCount: row.reviewCount,
    participantCount: row.participantCount,
    dimensions: {
      taste: row.taste,
      value: row.value,
      service: row.service,
      ambience: row.ambience,
    },
    tags: [],
    distanceKm: null,
    dining: {
      kind: 'window',
      parent: { id: row.canteenId, name: row.canteenName },
      floor: `${row.floorNumber}层`,
      campus: row.campusCode,
      description: row.description,
      windows: null,
      dishes: [],
    },
  }
}
/** 管理请求固定发起时凭证，不在前端推断资源权限。 */
export async function fetchManagedWindows(id: string, token: string, signal?: AbortSignal) {
  const result = await httpClient.get<ApiResponse<CanteenWindow[]>>(
    `/api/admin/restaurants/${encodeURIComponent(id)}/windows`,
    { headers: { Authorization: `Bearer ${token}` }, signal },
  )
  return result.data.data
}
export async function saveWindow(
  canteenId: string,
  id: string | null,
  input: WindowInput,
  token: string,
) {
  const url = id
    ? `/api/admin/canteen-windows/${encodeURIComponent(id)}`
    : `/api/admin/restaurants/${encodeURIComponent(canteenId)}/windows`
  const response = await httpClient.request<ApiResponse<CanteenWindow>>({
    url,
    method: id ? 'put' : 'post',
    data: input,
    headers: { Authorization: `Bearer ${token}` },
  })
  return response.data.data
}
export async function setWindowDeleted(id: string, deleted: boolean, token: string) {
  await httpClient.request({
    url: `/api/admin/canteen-windows/${encodeURIComponent(id)}${deleted ? '' : '/restore'}`,
    method: deleted ? 'delete' : 'post',
    headers: { Authorization: `Bearer ${token}` },
  })
}

/** 窗口相册与点评照片分开；URL经后端逐次检查可见性。 */
export interface WindowPhoto {
  id: string
  url: string
  cover: boolean
}
export async function fetchWindowPhotos(id: string, signal?: AbortSignal): Promise<WindowPhoto[]> {
  const result = await httpClient.get<ApiResponse<WindowPhoto[]>>(
    `/api/canteen-windows/${encodeURIComponent(id)}/photos`,
    { signal },
  )
  return result.data.data
}
/** 上传回执重复入册幂等，网络结果不明确时重试同一个fileId。 */
export async function addWindowPhoto(
  id: string,
  fileId: string,
  token: string,
): Promise<WindowPhoto[]> {
  const result = await httpClient.post<ApiResponse<WindowPhoto[]>>(
    `/api/admin/canteen-windows/${encodeURIComponent(id)}/photos`,
    { fileId },
    { headers: { Authorization: `Bearer ${token}` } },
  )
  return result.data.data
}
export async function removeWindowPhoto(id: string, fileId: string, token: string): Promise<void> {
  await httpClient.delete(
    `/api/admin/canteen-windows/${encodeURIComponent(id)}/photos/${encodeURIComponent(fileId)}`,
    { headers: { Authorization: `Bearer ${token}` } },
  )
}
