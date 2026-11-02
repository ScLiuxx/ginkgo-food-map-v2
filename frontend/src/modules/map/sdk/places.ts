import { loadAMap } from './amap'

export interface AMapPlace {
  id: string
  name: string
  address: string
  district: string
  category: string
  longitude: number
  latitude: number
}

/** 地点搜索不绑定SDK面板或地图，防止自动绘制第三方评分及重复点位。只在主动输入后调用。 */
export function searchAMapPlaces(
  keyword: string,
  page: number,
  signal: AbortSignal,
): Promise<{ places: AMapPlace[]; total: number }> {
  return new Promise((resolve, reject) => {
    let finished = false
    const timer = globalThis.setTimeout(() => fail(new Error('高德地点搜索超时，请重试。')), 15000)
    function clean() {
      finished = true
      globalThis.clearTimeout(timer)
      signal.removeEventListener('abort', abort)
    }
    function fail(reason: Error) {
      if (!finished) {
        clean()
        reject(reason)
      }
    }
    function abort() {
      fail(new globalThis.DOMException('请求已取消', 'AbortError'))
    }
    if (signal.aborted) {
      abort()
      return
    }
    signal.addEventListener('abort', abort, { once: true })
    void loadAMap()
      .then((sdk) => {
        if (finished) return
        sdk.plugin(['AMap.PlaceSearch'], () => {
          if (finished) return
          try {
            // 默认优先成都但不强制锁城；不传当前视口或用户精确位置。
            const search = new sdk.PlaceSearch({
              city: '成都市',
              citylimit: false,
              type: '050000',
              pageSize: 10,
              pageIndex: page,
              extensions: 'base',
            })
            search.search(keyword, (status, result) => {
              if (finished) return
              if (status === 'no_data') {
                clean()
                resolve({ places: [], total: 0 })
                return
              }
              if (status !== 'complete' || !result.poiList) {
                fail(new Error('高德地点搜索暂不可用，本站结果仍可使用。'))
                return
              }
              const seen = new Set<string>()
              const places = result.poiList.pois.flatMap((poi): AMapPlace[] => {
                const longitude = poi.location?.getLng(),
                  latitude = poi.location?.getLat()
                if (
                  !poi.id ||
                  !poi.name ||
                  longitude === undefined ||
                  latitude === undefined ||
                  !Number.isFinite(longitude) ||
                  !Number.isFinite(latitude) ||
                  Math.abs(longitude) > 180 ||
                  Math.abs(latitude) > 90 ||
                  seen.has(poi.id)
                )
                  return []
                seen.add(poi.id)
                return [
                  {
                    id: poi.id,
                    name: poi.name,
                    address: typeof poi.address === 'string' ? poi.address : '',
                    district: [poi.pname, poi.cityname, poi.adname]
                      .filter(Boolean)
                      .filter((value, index, all) => all.indexOf(value) === index)
                      .join(' · '),
                    category: poi.type?.split(';').filter(Boolean).at(-1) || '餐饮地点',
                    longitude,
                    latitude,
                  },
                ]
              })
              const count = Number(result.poiList.count)
              clean()
              resolve({
                places,
                total: Number.isFinite(count) ? Math.max(places.length, count) : places.length,
              })
            })
          } catch {
            fail(new Error('高德地点搜索暂不可用，请重试。'))
          }
        })
      })
      .catch(() => fail(new Error('高德地点搜索尚不可用，请检查地图配置或网络。')))
  })
}
