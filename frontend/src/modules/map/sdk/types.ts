/** 高德适配层只声明实际使用的公开能力，避免把SDK对象扩散到业务状态。 */
export interface SDKLatLng {
  getLng(): number
  getLat(): number
}
export interface SDKBounds {
  getSouthWest(): SDKLatLng
  getNorthEast(): SDKLatLng
}
export interface SDKMarker {
  getContent(): globalThis.HTMLElement | string
}
export interface SDKMap {
  getBounds(): SDKBounds
  on(event: string, callback: () => void): void
  off(event: string, callback: () => void): void
  add(markers: SDKMarker[]): void
  remove(markers: SDKMarker[]): void
  zoomIn(): void
  zoomOut(): void
  setZoomAndCenter(zoom: number, center: [number, number]): void
  destroy(): void
}
export interface AMapSDK {
  plugin(names: string[], callback: () => void): void
  PlaceSearch: new (options: {
    city: string
    citylimit: boolean
    type: string
    pageSize: number
    pageIndex: number
    extensions: string
  }) => {
    search(keyword: string, callback: (status: string, result: SDKPlaceResult) => void): void
  }
  /** 浏览器返回WGS84；只在用户主动定位后转换成地图使用的GCJ-02。 */
  convertFrom(
    position: [number, number],
    type: 'gps',
    callback: (status: string, result: { locations?: SDKLatLng[] }) => void,
  ): void
  Map: new (
    container: globalThis.HTMLElement,
    options: { center: [number, number]; zoom: number; viewMode: string; mapStyle?: string },
  ) => SDKMap
  Marker: new (options: {
    position: [number, number]
    content: globalThis.HTMLElement
    anchor: string
    zIndex?: number
  }) => SDKMarker
}
/** 仅声明地点搜索的基础字段，不读取或借用第三方评分、照片作为本站数据。 */
export interface SDKPlaceResult {
  info?: string
  poiList?: {
    count: number | string
    pois: {
      id: string
      name: string
      address?: string | string[]
      pname?: string
      cityname?: string
      adname?: string
      type?: string
      location?: SDKLatLng
    }[]
  }
}
