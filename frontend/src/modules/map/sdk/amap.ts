import type { AMapSDK } from './types'
import loader from '@amap/amap-jsapi-loader'

let pending: Promise<AMapSDK> | undefined
/** 统一加载与失败后重试；浏览器只接收JS标识和同源安全代理地址，不下发服务端安全密钥。 */
export function loadAMap(): Promise<AMapSDK> {
  const key = import.meta.env.VITE_AMAP_KEY
  const serviceHost = import.meta.env.VITE_AMAP_SERVICE_HOST
  if (!key) return Promise.reject(new Error('尚未配置高德地图，请设置本地 VITE_AMAP_KEY。'))
  if (!serviceHost)
    return Promise.reject(new Error('尚未配置高德安全代理地址 VITE_AMAP_SERVICE_HOST。'))
  if (!serviceHost.startsWith('/') || serviceHost.startsWith('//'))
    return Promise.reject(new Error('高德安全代理必须使用同源路径。'))
  const runtime = globalThis as typeof globalThis & {
    _AMapSecurityConfig?: { serviceHost: string }
  }
  runtime._AMapSecurityConfig = { serviceHost: `${globalThis.location.origin}${serviceHost}` }
  if (!pending)
    pending = loader.load({ key, version: '2.0', plugins: [] }).catch((error: unknown) => {
      pending = undefined
      throw error
    })
  return pending!
}
