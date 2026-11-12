import { defineStore } from 'pinia'
import { shallowRef } from 'vue'
import type { CatalogFilters } from '@/modules/restaurant'
import type { MapCamera } from '../api/mapApi'
import type { ViewportSnapshot } from './viewport'

export interface MapBrowsingSnapshot {
  filters: CatalogFilters
  camera: MapCamera | null
  viewport: ViewportSnapshot
  selectedId: string | null
  sheetSnap: 'collapsed' | 'half' | 'full'
  scrollTop: number
}

/** 只缓存当前应用会话的一份地图现场；不持久化位置，也不保留SDK实例或在途请求。 */
export const useMapBrowsing = defineStore('map-browsing', () => {
  const snapshot = shallowRef<MapBrowsingSnapshot | null>(null)
  function remember(value: MapBrowsingSnapshot) {
    // 快照均为公开的JSON资料；深拷贝隔离离开后的响应、筛选修改及Vue代理引用。
    snapshot.value = JSON.parse(JSON.stringify(value)) as MapBrowsingSnapshot
  }
  return { snapshot, remember }
})
