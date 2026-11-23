import type { AMapSDK } from './types'

export function createMyLocationMarker(sdk: AMapSDK, center: [number, number]) {
  const dot = globalThis.document.createElement('span')
  dot.className = 'amap-my-location'
  dot.setAttribute('role', 'img')
  dot.setAttribute('aria-label', '我的位置（点击定位时获取）')
  dot.title = '我的位置（单次定位，不持续跟踪）'
  return new sdk.Marker({ position: center, content: dot, anchor: 'center', zIndex: 200 })
}
