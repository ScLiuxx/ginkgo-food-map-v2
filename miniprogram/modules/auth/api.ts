import { request } from '../../shared/api/request'
import type { AuthSession, CurrentUser } from '../../shared/auth/session'

/**
 * 账号接口。
 *
 * 小程序第一批沿用现有用户名/邮箱 + 密码登录，不引入微信 openid 绑定；
 * 登录成功后只保存后端返回的正式 JWT 会话。
 */
export function login(username: string, password: string): Promise<AuthSession> {
  return request<AuthSession>({
    path: '/auth/login',
    method: 'POST',
    data: { username, password },
    auth: 'none',
  })
}

/** 刷新时由服务端重新确认身份；本地有令牌不代表会话仍然有效。 */
export function fetchCurrentUser(): Promise<CurrentUser> {
  return request<CurrentUser>({ path: '/auth/me', auth: 'required' })
}

/** 撤销当前服务端会话；成功后才允许清理本地令牌。 */
export function logout(): Promise<void> {
  return request<void>({ path: '/auth/logout', method: 'POST', auth: 'required' })
}
