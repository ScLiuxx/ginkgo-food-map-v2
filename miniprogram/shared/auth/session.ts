/**
 * 登录态本地存储。
 *
 * 设计要点：
 * - 只保存访问令牌和公开身份，不保存密码；
 * - 读操作每次都从 storage 取最新值，页面切换或退出后不会继续使用内存旧值；
 * - clearSession 支持传入“产生 401 的令牌”，旧请求的 401 不能清除刚完成的新登录。
 */
export interface CurrentUser {
  id: number
  username: string
  roles: string[]
}

export interface AuthSession {
  accessToken: string
  tokenType: 'Bearer'
  expiresAt: string
  user: CurrentUser
}

const TOKEN_KEY = 'ginkgo.accessToken'
const USER_KEY = 'ginkgo.currentUser'

function readStorage<T>(key: string): T | null {
  try {
    const value = wx.getStorageSync(key)
    return value === '' || value === null || value === undefined ? null : (value as T)
  } catch {
    return null
  }
}

export function getToken(): string | null {
  const token = readStorage<string>(TOKEN_KEY)
  return typeof token === 'string' && token ? token : null
}

export function getCurrentUser(): CurrentUser | null {
  const user = readStorage<CurrentUser>(USER_KEY)
  if (!user || typeof user.id !== 'number' || typeof user.username !== 'string') return null
  return user
}

export function isLoggedIn(): boolean {
  return getToken() !== null
}

/** 登录或注册并登录成功后写入服务端返回的正式会话。 */
export function saveSession(session: AuthSession): void {
  try {
    wx.setStorageSync(TOKEN_KEY, session.accessToken)
    wx.setStorageSync(USER_KEY, session.user)
  } catch {
    // 存储不可用时本次会话无法持久化；页面仍会通过请求结果提示用户重新登录。
  }
}

/** 只清除指定令牌对应的会话，避免过期请求撤销新登录。 */
export function clearSession(expectedToken?: string): void {
  const current = getToken()
  if (expectedToken !== undefined && current !== null && current !== expectedToken) return
  try {
    wx.removeStorageSync(TOKEN_KEY)
    wx.removeStorageSync(USER_KEY)
  } catch {
    // 忽略存储异常，下一次请求仍会被服务端拒绝并要求重新登录。
  }
}
