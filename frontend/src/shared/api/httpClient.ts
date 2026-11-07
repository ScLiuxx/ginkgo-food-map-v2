import axios from 'axios'
import { clearAccessToken, getAccessToken } from './authSession'

export const httpClient = axios.create({
  timeout: 10_000,
  headers: {
    Accept: 'application/json',
  },
})

// 公开接口不附带旧凭证，防止已过期的 Bearer 令牌阻止用户重新登录或注册。
const publicAuthPaths = new Set([
  '/auth/login',
  '/auth/register',
  '/auth/password-reset',
  '/auth/email/code/register',
  '/auth/email/code/password-reset',
])

httpClient.interceptors.request.use((config) => {
  const token = getAccessToken()
  // 餐厅与点评主列表GET默认匿名读取；管理资料和所有写操作仍携带凭证。
  // 避免过期登录阻止匿名浏览，也避免公开目录不必要地依赖Redis会话服务。
  const path = (config.url ?? '').split('?')[0] ?? ''
  const publicRestaurantRead =
    config.method === 'get' &&
    (path === '/api/content-likes' ||
      path === '/api/restaurant-categories' ||
      path === '/api/restaurants' ||
      /^\/api\/restaurants\/[^/]+$/.test(path) ||
      /^\/api\/restaurants\/[^/]+\/photos(?:\/[^/]+)?$/.test(path) ||
      /^\/api\/restaurants\/[^/]+\/reviews$/.test(path) ||
      /^\/api\/restaurants\/[^/]+\/reviews\/authors\/[^/]+\/history$/.test(path) ||
      /^\/api\/reviews\/[^/]+\/replies$/.test(path))
  if (
    token &&
    !publicAuthPaths.has(path) &&
    !path.startsWith('/api/public/') &&
    !publicRestaurantRead
  ) {
    config.headers.Authorization ??= `Bearer ${token}`
  }
  return config
})

httpClient.interceptors.response.use(undefined, (reason: unknown) => {
  if (axios.isAxiosError(reason) && reason.response?.status === 401) {
    const authorization = reason.config?.headers?.Authorization
    if (typeof authorization === 'string' && authorization.startsWith('Bearer ')) {
      clearAccessToken(authorization.slice(7))
    }
  }
  return Promise.reject(reason)
})
