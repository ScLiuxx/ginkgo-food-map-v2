import { onScopeDispose, ref, shallowRef } from 'vue'
import type { AMapSDK } from '../sdk/types'

export interface MyLocation {
  center: [number, number]
  accuracy: number
  timestamp: number
}

/** 单次定位的临时状态：不写入账号、存储或日志；销毁后忽略浏览器的迟到回调。 */
export function useMyLocation(getSDK: () => Pick<AMapSDK, 'convertFrom'> | undefined) {
  const locating = ref(false)
  const location = shallowRef<MyLocation | null>(null)
  const message = ref('')
  let disposed = false
  let cancel: (() => void) | undefined

  function locate() {
    const sdk = getSDK()
    if (disposed || locating.value || !sdk) return
    location.value = null
    if (!globalThis.isSecureContext) {
      message.value = '定位需要 HTTPS；本地开发请通过 localhost 或 127.0.0.1 打开。'
      return
    }
    if (!globalThis.navigator.geolocation) {
      message.value = '当前浏览器不支持定位，请手动移动地图。'
      return
    }
    locating.value = true
    message.value = '正在申请一次定位，请在浏览器提示中选择是否允许。'
    let finished = false
    // 浏览器的timeout不涵盖等待授权的时间；额外设置总时限，避免界面一直等待。
    const timer = globalThis.setTimeout(() => fail('定位等待超时，请确认浏览器权限后重试。'), 20000)
    function finish() {
      finished = true
      globalThis.clearTimeout(timer)
      locating.value = false
      cancel = undefined
    }
    function fail(text: string) {
      if (finished || disposed) return
      finish()
      message.value = text
    }
    cancel = finish
    try {
      globalThis.navigator.geolocation.getCurrentPosition(
        (position) => {
          if (finished || disposed) return
          const { longitude, latitude, accuracy } = position.coords
          if (
            !Number.isFinite(longitude) ||
            !Number.isFinite(latitude) ||
            Math.abs(longitude) > 180 ||
            Math.abs(latitude) > 90
          ) {
            fail('浏览器返回的位置无效，请重试。')
            return
          }
          message.value = '已获得位置，正在转换地图坐标…'
          try {
            sdk!.convertFrom([longitude, latitude], 'gps', (status, result) => {
              if (finished || disposed) return
              const point = result.locations?.[0]
              if (
                status !== 'complete' ||
                !point ||
                !Number.isFinite(point.getLng()) ||
                !Number.isFinite(point.getLat())
              ) {
                fail('位置坐标转换失败，请稍后重试。')
                return
              }
              finish()
              location.value = {
                center: [point.getLng(), point.getLat()],
                accuracy,
                timestamp: position.timestamp,
              }
              const time = new Date(position.timestamp).toLocaleTimeString([], {
                hour: '2-digit',
                minute: '2-digit',
              })
              const precision =
                Number.isFinite(accuracy) && accuracy >= 0
                  ? `，浏览器报告精度约 ${Math.round(accuracy)} 米`
                  : ''
              message.value = `${time} 获取的位置${precision}；不会持续跟踪，移动后请重新点击。`
            })
          } catch {
            fail('位置坐标转换失败，请稍后重试。')
          }
        },
        (error) => {
          const messages: Record<number, string> = {
            1: '未获定位权限。可在浏览器地址栏的网站权限中允许后重试，也可以手动移动地图。',
            2: '暂时无法获取位置，请检查系统定位服务和网络，或手动移动地图。',
            3: '获取位置超时，请稍后重试，或手动移动地图。',
          }
          fail(messages[error.code] || '定位失败，请稍后重试。')
        },
        { enableHighAccuracy: true, timeout: 8000, maximumAge: 0 },
      )
    } catch {
      fail('浏览器无法启动定位，请检查网站权限后重试。')
    }
  }
  onScopeDispose(() => {
    disposed = true
    cancel?.()
    location.value = null
  })
  return { locating, location, message, locate }
}
