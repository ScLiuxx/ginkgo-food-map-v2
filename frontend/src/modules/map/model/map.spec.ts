import { describe, expect, it } from 'vitest'
import { mapExamples, navigationUrl, placeNavigationUrl } from './map'

describe('高德导航的真实坐标边界', () => {
  const real = {
    ...mapExamples[0]!,
    id: '42',
    needsLocation: false,
    longitude: 103.94,
    latitude: 30.75,
  }
  it('示例、待定位和非法坐标不生成真实导航', () => {
    expect(navigationUrl(mapExamples[0]!)).toBeNull()
    expect(navigationUrl({ ...real, needsLocation: true })).toBeNull()
    expect(navigationUrl({ ...real, latitude: NaN })).toBeNull()
    expect(navigationUrl({ ...real, longitude: null })).toBeNull()
  })
  it('外部地点独立导航，拒绝非法坐标并编码名称，不需要伪造本站ID', () => {
    const place = { name: '外部店 & mode=car', longitude: 104.1, latitude: 30.6 }
    const url = new URL(placeNavigationUrl(place)!)
    expect(url.pathname).toBe('/marker')
    expect(url.searchParams.get('position')).toBe('104.1,30.6')
    expect(url.searchParams.get('name')).toBe('外部店 & mode=car')
    expect(url.searchParams.has('from')).toBe(false)
    expect(placeNavigationUrl({ ...place, latitude: NaN })).toBeNull()
    expect(placeNavigationUrl({ ...place, longitude: 0 })).toBeNull()
  })
  it('仅传目的地与坐标系，特殊店名不能注入参数，不再固定校区起点', () => {
    const url = new URL(navigationUrl({ ...real, name: '店名 & mode=car#一店' })!)
    expect(url.origin).toBe('https://uri.amap.com')
    expect(url.pathname).toBe('/marker')
    expect(url.searchParams.get('position')).toBe('103.94,30.75')
    expect(url.searchParams.get('name')).toBe('店名 & mode=car#一店')
    expect(url.searchParams.get('coordinate')).toBe('gaode')
    expect(url.searchParams.has('mode')).toBe(false)
    expect(url.searchParams.has('from')).toBe(false)
    expect(url.searchParams.get('callnative')).toBe('1')
  })
})
