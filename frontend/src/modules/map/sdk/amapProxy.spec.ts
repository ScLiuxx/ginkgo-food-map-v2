import { PassThrough } from 'node:stream'
import type { IncomingMessage, ServerResponse } from 'node:http'
import https from 'node:https'
import type { ViteDevServer } from 'vite'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { amapProxy } from '../../../../config/amapProxy'

vi.mock('node:https', () => ({ default: { get: vi.fn() } }))

/** 使用假上游验证代理边界，不使用本机凭据或真实高德配额。 */
describe('高德安全代理', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })
  function proxy(url: string, method = 'GET', secret = 'server-only-secret') {
    let handler!: (req: IncomingMessage, res: ServerResponse) => void
    const plugin = amapProxy({ VITE_AMAP_KEY: 'configured-key', AMAP_SECURITY_JS_CODE: secret })
    const hook = plugin.configureServer as (server: ViteDevServer) => void
    hook({
      middlewares: {
        use(_path: string, middleware: typeof handler) {
          handler = middleware
        },
      },
    } as unknown as ViteDevServer)
    const response = new PassThrough() as unknown as ServerResponse
    response.setHeader = vi.fn().mockReturnValue(response)
    response.end = vi.fn().mockReturnValue(response)
    handler({ url, method } as IncomingMessage, response)
    return response
  }
  it('拒绝任意路径及非GET请求，不访问上游', () => {
    expect(proxy('/https://example.com').statusCode).toBe(404)
    expect(proxy('/v3/place/text').statusCode).toBe(400)
    expect(proxy('/v3/place/around?keywords=eat').statusCode).toBe(404)
    expect(proxy('/v3/place/detail?id=x').statusCode).toBe(404)
    expect(proxy('/v3/log/init', 'POST').statusCode).toBe(405)
    expect(https.get).not.toHaveBeenCalled()
  })
  it('餐饮搜索限定参数和精确路径，不接受空关键词或越界页码', () => {
    const outgoing = new PassThrough()
    vi.mocked(https.get).mockReturnValue(outgoing as never)
    proxy('/v3/place/text?keywords=eat&types=hotel&offset=99&extensions=all&city=Beijing&page=2')
    const target = vi.mocked(https.get).mock.calls[0]![0] as URL
    expect(target.searchParams.get('types')).toBe('050000')
    expect(target.searchParams.get('extensions')).toBe('base')
    expect(target.searchParams.get('offset')).toBe('10')
    expect(target.searchParams.get('city')).toBe('成都市')
    expect(target.searchParams.get('page')).toBe('2')
    expect(proxy('/v3/place/text?keywords=eat&page=101').statusCode).toBe(400)
    expect(proxy('/v3/place/text?keywords=eat&page=0').statusCode).toBe(400)
    expect(proxy('/v3/place/text?keywords=' + 'a'.repeat(101)).statusCode).toBe(400)
    expect(https.get).toHaveBeenCalledTimes(1)
  })
  it('缺少安全密钥时返回安全错误，不建立请求', () => {
    const response = proxy('/v3/log/init', 'GET', '')
    expect(response.statusCode).toBe(503)
    expect(response.end).toHaveBeenCalledWith('{"code":"MAP_PROXY_NOT_CONFIGURED"}')
    expect(https.get).not.toHaveBeenCalled()
  })
  it('仅增加精确的坐标转换路径，不放开其他助手接口', () => {
    const outgoing = new PassThrough()
    vi.mocked(https.get).mockReturnValue(outgoing as never)
    proxy('/v3/assistant/coordinate/convert?coordsys=gps&locations=104,30')
    const target = vi.mocked(https.get).mock.calls[0]![0] as URL
    expect(target.origin).toBe('https://restapi.amap.com')
    expect(target.pathname).toBe('/v3/assistant/coordinate/convert')
    expect(proxy('/v3/assistant/coordinate/convert/extra').statusCode).toBe(404)
    expect(proxy('/v3/assistant/inputtips').statusCode).toBe(404)
    expect(https.get).toHaveBeenCalledTimes(1)
  })
  it('固定上游和配置Key，替换客户端安全密钥，密钥不在响应中', () => {
    const outgoing = new PassThrough()
    vi.mocked(https.get).mockReturnValue(outgoing as never)
    const response = proxy('/v3/log/init?key=untrusted&jscode=untrusted')
    const target = vi.mocked(https.get).mock.calls[0]![0] as URL
    expect(target.origin).toBe('https://restapi.amap.com')
    expect(target.searchParams.get('key')).toBe('configured-key')
    expect(target.searchParams.get('jscode')).toBe('server-only-secret')
    outgoing.emit('error', new Error('contains-server-only-secret'))
    expect(response.end).toHaveBeenCalledWith('{"code":"MAP_UPSTREAM_UNAVAILABLE"}')
  })
})
