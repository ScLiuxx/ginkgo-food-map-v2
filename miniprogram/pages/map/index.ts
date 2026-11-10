import { toErrorMessage } from '../../shared/api/request'
import { fetchMapPoints, type MapPoint } from '../../modules/map/api'

interface MapMarker {
  id: number
  latitude: number
  longitude: number
  width: number
  height: number
  iconPath: string
  callout: {
    content: string
    color: string
    fontSize: number
    borderRadius: number
    bgColor: string
    padding: number
    display: 'BYCLICK'
  }
}

interface MapData {
  latitude: number
  longitude: number
  scale: number
  markers: MapMarker[]
  points: MapPoint[]
  matchedCount: number
  returnedCount: number
  truncated: boolean
  loading: boolean
  locating: boolean
  located: boolean
  showLocation: boolean
  locationHint: string
  errorMessage: string
}

interface MapCustom {
  requestSequence: number
  regionTimer: number
  lastRegion: WxMapRegion | null
  mapContext?: WxMapContext
  locate(showPermissionPrompt: boolean): void
  onLocateTap(): void
  applyCenter(latitude: number, longitude: number): void
  fallbackToCampus(hint: string): void
  fetchRegion(region: WxMapRegion): Promise<void>
  retry(): void
}

interface WxMapRegionChangeEvent {
  type: string
  causedBy: string
}

interface WxMarkerTapEvent {
  detail: { markerId: number }
}

/** 未定位时使用的电子科技大学清水河校区中心，仅作为地图初始视野。 */
const CAMPUS_CENTER = { latitude: 30.7544, longitude: 103.9312 }
const CAMPUS_SCALE = 16
const SEARCH_SPAN = 0.012

/** 以中心点生成近似正方形视野；纬度跨度固定，经度按纬度余弦补偿。 */
function boundsAround(latitude: number, longitude: number): WxMapRegion {
  const latitudeSpan = SEARCH_SPAN
  const longitudeSpan = SEARCH_SPAN / Math.max(Math.cos((latitude * Math.PI) / 180), 0.2)
  return {
    southwest: {
      latitude: Math.max(-90, latitude - latitudeSpan),
      longitude: Math.max(-180, longitude - longitudeSpan),
    },
    northeast: {
      latitude: Math.min(90, latitude + latitudeSpan),
      longitude: Math.min(180, longitude + longitudeSpan),
    },
  }
}

function isValidRegion(region: WxMapRegion): boolean {
  return (
    Number.isFinite(region.southwest.latitude) &&
    Number.isFinite(region.southwest.longitude) &&
    Number.isFinite(region.northeast.latitude) &&
    Number.isFinite(region.northeast.longitude) &&
    region.northeast.latitude > region.southwest.latitude &&
    region.northeast.longitude > region.southwest.longitude
  )
}

/**
 * 地图找店。
 *
 * 点位只按当前视野读取，拖动或缩放结束后节流刷新；标记编号使用当前数组下标，
 * 因此每次响应都整体替换 markers 和 points，避免旧标记指向新数组的错误店铺。
 */
Page<MapData, MapCustom>({
  data: {
    latitude: CAMPUS_CENTER.latitude,
    longitude: CAMPUS_CENTER.longitude,
    scale: CAMPUS_SCALE,
    markers: [],
    points: [],
    matchedCount: 0,
    returnedCount: 0,
    truncated: false,
    loading: false,
    locating: false,
    located: false,
    showLocation: false,
    locationHint: '',
    errorMessage: '',
  },

  requestSequence: 0,
  regionTimer: 0,
  lastRegion: null,

  onLoad() {
    // 先以校园中心渲染地图，定位结果返回后再移动视野，避免地图空白等待授权。
    this.setData({ latitude: CAMPUS_CENTER.latitude, longitude: CAMPUS_CENTER.longitude })
  },

  onReady() {
    this.mapContext = wx.createMapContext('restaurantMap', this)
    this.locate(false)
  },

  onShow() {
    const tabBar = this.getTabBar?.()
    if (tabBar) tabBar.setData({ selected: 1 })
    tabBar?.refreshUnread?.()
  },

  onUnload() {
    clearTimeout(this.regionTimer)
    this.requestSequence++
  },

  /** 定位成功使用真实视野；失败时退回校园中心，并明确提示不是用户当前位置。 */
  locate(showPermissionPrompt: boolean) {
    if (this.data.locating) return
    this.setData({ locating: true, locationHint: '' })
    wx.getLocation({
      type: 'gcj02',
      isHighAccuracy: true,
      success: (result) => {
        this.setData({ locating: false, located: true, showLocation: true })
        this.applyCenter(result.latitude, result.longitude)
      },
      fail: () => {
        this.setData({ locating: false })
        this.fallbackToCampus('未获得定位权限，当前显示校园中心附近店铺。')
        if (showPermissionPrompt) {
          wx.showModal({
            title: '无法获取位置',
            content: '可以在设置中允许定位，也可以直接拖动地图浏览其他区域。',
            confirmText: '去设置',
            cancelText: '稍后',
            success: (result) => {
              if (!result.confirm) return
              wx.openSetting({
                success: (setting) => {
                  if (setting.authSetting['scope.userLocation']) this.locate(false)
                },
              })
            },
          })
        }
      },
    })
  },

  onLocateTap() {
    this.locate(true)
  },

  applyCenter(latitude: number, longitude: number) {
    this.setData({ latitude, longitude, scale: CAMPUS_SCALE })
    void this.fetchRegion(boundsAround(latitude, longitude))
  },

  fallbackToCampus(hint: string) {
    this.setData({
      latitude: CAMPUS_CENTER.latitude,
      longitude: CAMPUS_CENTER.longitude,
      scale: CAMPUS_SCALE,
      located: false,
      showLocation: false,
      locationHint: hint,
    })
    void this.fetchRegion(boundsAround(CAMPUS_CENTER.latitude, CAMPUS_CENTER.longitude))
  },

  onRegionChange(event: WxMapRegionChangeEvent) {
    // 程序化移动视野也会触发 update，不能把它当成用户操作，否则会反复请求。
    if (event.type !== 'end' || event.causedBy === 'update') return
    clearTimeout(this.regionTimer)
    this.regionTimer = setTimeout(() => {
      this.mapContext?.getRegion({
        success: (region) => {
          if (isValidRegion(region)) void this.fetchRegion(region)
        },
      })
    }, 320)
  },

  async fetchRegion(region: WxMapRegion) {
    if (!isValidRegion(region)) return
    const sequence = ++this.requestSequence
    this.lastRegion = region
    this.setData({ loading: true, errorMessage: '' })
    try {
      const page = await fetchMapPoints(region)
      if (sequence !== this.requestSequence) return
      const markers = page.points.map((point, index): MapMarker => ({
        id: index,
        latitude: point.latitude,
        longitude: point.longitude,
        width: 30,
        height: 38,
        iconPath: '/assets/map/marker.png',
        callout: {
          content: point.name,
          color: '#08192b',
          fontSize: 12,
          borderRadius: 10,
          bgColor: '#ffffff',
          padding: 8,
          display: 'BYCLICK',
        },
      }))
      this.setData({
        points: page.points,
        markers,
        matchedCount: page.matchedCount,
        returnedCount: page.returnedCount,
        truncated: page.truncated,
        loading: false,
      })
    } catch (reason) {
      if (sequence !== this.requestSequence) return
      this.setData({ loading: false, errorMessage: toErrorMessage(reason) })
    }
  },

  onMarkerTap(event: WxMarkerTapEvent) {
    const point = this.data.points[Number(event.detail.markerId)]
    if (!point) return
    wx.navigateTo({
      url: `/pages/restaurant/detail/index?id=${encodeURIComponent(point.id)}`,
    })
  },

  retry() {
    if (this.lastRegion) {
      void this.fetchRegion(this.lastRegion)
      return
    }
    void this.fetchRegion(boundsAround(this.data.latitude, this.data.longitude))
  },
})
