import { API_BASE_URL } from '../../config/env'
import { clearSession, getToken } from '../auth/session'
import type { ApiEnvelope } from './types'

/**
 * 统一请求层。
 *
 * 约定：
 * - 只解包后端统一响应体，业务页面拿到的直接是 data；
 * - 公开读取显式传 auth: 'none'，避免本地过期令牌阻止游客浏览；
 * - 需要登录的请求传 auth: 'required'，没有令牌时在本地直接拒绝；
 * - 收到 401 时只清除“本次请求实际使用的令牌”，旧请求不能撤销新登录。
 */
export type AuthMode = 'auto' | 'none' | 'required'

export interface RequestOptions {
  path: string
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  data?: unknown
  auth?: AuthMode
  timeout?: number
}

/** 稳定的业务错误类型，页面可据此区分登录失效与普通失败。 */
export class ApiError extends Error {
  readonly code: string
  readonly status: number

  constructor(message: string, code: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

/** 常见错误码的兜底文案；服务端返回的 message 优先，避免覆盖更具体的原因。 */
const FRIENDLY_MESSAGES: Record<string, string> = {
  AUTH_UNAUTHENTICATED: '登录已失效，请重新登录。',
  AUTH_INVALID_CREDENTIALS: '用户名或密码不正确。',
  AUTH_LOGIN_LOCKED: '登录失败次数过多，请稍后再试。',
  AUTH_SERVICE_UNAVAILABLE: '登录服务暂时不可用，请稍后重试。',
  AUTH_FORBIDDEN: '当前账号没有此操作权限。',
  COMMON_VALIDATION_FAILED: '提交内容不符合要求，请检查后重试。',
  NETWORK_ERROR: '网络连接失败，请检查网络后重试。',
}

/** 上传等自建请求也要复用同一套响应体识别与错误文案。 */
export function asEnvelope(value: unknown): ApiEnvelope<unknown> | null {
  if (!value || typeof value !== 'object') return null
  const row = value as Partial<ApiEnvelope<unknown>>
  if (typeof row.success !== 'boolean' || typeof row.code !== 'string') return null
  return row as ApiEnvelope<unknown>
}

export function messageFrom(body: ApiEnvelope<unknown> | null, status: number): string {
  const serverMessage = body && typeof body.message === 'string' ? body.message.trim() : ''
  if (serverMessage) return serverMessage
  const codeMessage = body ? FRIENDLY_MESSAGES[body.code] : ''
  if (codeMessage) return codeMessage
  if (status === 401) return '登录已失效，请重新登录。'
  if (status === 403) return '当前账号没有此操作权限。'
  if (status === 429) return '操作过于频繁，请稍后再试。'
  if (status === 503) return '服务暂时不可用，请稍后重试。'
  return '请求失败，请稍后重试。'
}

/** 将任意异常转为可直接展示的中文文案；未知异常不回显堆栈或内部细节。 */
export function toErrorMessage(reason: unknown): string {
  if (reason instanceof ApiError) return reason.message
  if (reason instanceof Error && reason.message) return reason.message
  return '操作失败，请稍后重试。'
}

export function request<T>(options: RequestOptions): Promise<T> {
  const method = options.method ?? 'GET'
  const authMode = options.auth ?? 'auto'
  const token = authMode === 'none' ? null : getToken()

  if (authMode === 'required' && !token) {
    return Promise.reject(new ApiError('请先登录。', 'AUTH_UNAUTHENTICATED', 401))
  }

  const header: Record<string, string> = { Accept: 'application/json' }
  if (token) header.Authorization = `Bearer ${token}`
  if (options.data !== undefined) header['Content-Type'] = 'application/json'

  return new Promise<T>((resolve, reject) => {
    wx.request({
      url: `${API_BASE_URL}${options.path}`,
      method,
      data: options.data,
      header,
      timeout: options.timeout ?? 10000,
      success(result) {
        // 204 没有响应体，删除类接口成功时直接返回空结果。
        if (result.statusCode === 204) {
          resolve(undefined as T)
          return
        }
        const body = asEnvelope(result.data)
        if (result.statusCode >= 200 && result.statusCode < 300) {
          if (!body) {
            reject(new ApiError('响应格式异常，请稍后重试。', 'INVALID_RESPONSE', result.statusCode))
            return
          }
          if (body.success) {
            resolve(body.data as T)
            return
          }
          reject(new ApiError(messageFrom(body, result.statusCode), body.code, result.statusCode))
          return
        }
        if (result.statusCode === 401) {
          // 仅清理产生本次失败的令牌；新登录写入的令牌不受旧请求影响。
          if (token) clearSession(token)
        }
        reject(
          new ApiError(
            messageFrom(body, result.statusCode),
            body?.code ?? 'HTTP_ERROR',
            result.statusCode,
          ),
        )
      },
      fail() {
        reject(new ApiError(FRIENDLY_MESSAGES.NETWORK_ERROR, 'NETWORK_ERROR', 0))
      },
    })
  })
}
