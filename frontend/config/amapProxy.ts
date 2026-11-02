import https from 'node:https'
import type { Plugin } from 'vite'

/** 仅开发服务器使用的高德安全代理。固定上游/地图路径/Key，不接受任意URL；安全密钥永不返回浏览器。 */
export function amapProxy(env: Record<string, string>): Plugin {
  return {
    name: 'ginkgo-amap-security-proxy',
    configureServer(server) {
      server.middlewares.use('/_AMapService', (request, response) => {
        const fail = (status: number, code: string) => {
          if (!response.headersSent) {
            response.statusCode = status
            response.setHeader('Content-Type', 'application/json')
            response.setHeader('Cache-Control', 'no-store')
          }
          response.end(JSON.stringify({ code }))
        }
        if (request.method !== 'GET') {
          fail(405, 'MAP_METHOD_NOT_ALLOWED')
          return
        }
        if (!env.AMAP_SECURITY_JS_CODE?.trim() || !env.VITE_AMAP_KEY?.trim()) {
          fail(503, 'MAP_PROXY_NOT_CONFIGURED')
          return
        }
        const local = new URL(request.url || '/', 'http://localhost')
        // 仅开放精确的餐饮关键词路径，不能转发周边/详情或其他类别查询。
        if (
          !/^\/(?:v[34]\/(?:map(?:\/.*)?|log\/init)|v3\/assistant\/coordinate\/convert|v3\/place\/text)$/.test(
            local.pathname,
          ) ||
          local.search.length > 8192
        ) {
          fail(404, 'MAP_PATH_NOT_ALLOWED')
          return
        }
        if (local.pathname === '/v3/place/text') {
          const keyword = local.searchParams.get('keywords')?.trim()
          const page = local.searchParams.get('page') || '1'
          if (!keyword || keyword.length > 100 || !/^[1-9]\d?$|^100$/.test(page)) {
            fail(400, 'MAP_SEARCH_INVALID_QUERY')
            return
          }
          local.searchParams.set('types', '050000')
          local.searchParams.set('offset', '10')
          local.searchParams.set('extensions', 'base')
          local.searchParams.set('city', '成都市')
          local.searchParams.set('citylimit', 'false')
        }
        const upstream = new URL(
          local.pathname.startsWith('/v4/map/styles')
            ? 'https://webapi.amap.com'
            : 'https://restapi.amap.com',
        )
        upstream.pathname = local.pathname
        upstream.search = local.search
        upstream.searchParams.set('key', env.VITE_AMAP_KEY.trim())
        upstream.searchParams.set('jscode', env.AMAP_SECURITY_JS_CODE.trim())
        // 不记录上游URL或错误对象，防止jscode经日志泄露；禁止自动跟随重定向。
        const outgoing = https.get(upstream, { timeout: 8000 }, (incoming) => {
          if (incoming.statusCode && incoming.statusCode >= 300 && incoming.statusCode < 400) {
            incoming.resume()
            fail(502, 'MAP_UPSTREAM_REDIRECT')
            return
          }
          response.statusCode = incoming.statusCode || 502
          response.setHeader('Content-Type', incoming.headers['content-type'] || 'application/json')
          response.setHeader('Cache-Control', 'no-store')
          incoming.on('error', () => {
            if (!response.writableEnded) fail(502, 'MAP_UPSTREAM_UNAVAILABLE')
          })
          incoming.pipe(response)
        })
        outgoing.on('timeout', () => outgoing.destroy())
        outgoing.on('error', () => {
          if (!response.writableEnded) fail(502, 'MAP_UPSTREAM_UNAVAILABLE')
        })
        response.on('close', () => outgoing.destroy())
      })
    },
  }
}
