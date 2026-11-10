import { request } from '../../shared/api/request'
import type { AuthSession, CurrentUser } from '../../shared/auth/session'

/**
 * 账号接口。
 *
 * 小程序使用邮箱 + 密码登录，不引入微信 openid 绑定；
 * 后端登录字段仍叫 username，但接受注册邮箱，因此这里只对字段名做适配。
 * 登录成功后只保存后端返回的正式 JWT 会话。
 */
export function login(email: string, password: string): Promise<AuthSession> {
  return request<AuthSession>({
    path: '/auth/login',
    method: 'POST',
    data: { username: email, password },
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

export type EmailCodePurpose = 'register' | 'password-reset'

/**
 * 发送注册或找回密码验证码。
 *
 * 两种用途共用发送配额但验证码隔离；接口成功不代表邮件一定已投递到收件箱。
 */
export function sendEmailCode(purpose: EmailCodePurpose, email: string): Promise<void> {
  return request<void>({
    path: `/auth/email/code/${purpose}`,
    method: 'POST',
    data: { email },
    auth: 'none',
  })
}

export interface RegisterInput {
  email: string
  verificationCode: string
  username: string
  password: string
}

/** 注册并直接建立会话；服务端只分配普通用户角色。 */
export function registerAndLogin(input: RegisterInput): Promise<AuthSession> {
  return request<AuthSession>({
    path: '/auth/register-and-login',
    method: 'POST',
    data: input,
    auth: 'none',
  })
}

/** 设置新密码；成功后旧会话全部失效，需要重新登录。 */
export async function resetPassword(
  email: string,
  verificationCode: string,
  newPassword: string,
): Promise<string> {
  const data = await request<{ username: string }>({
    path: '/auth/password-reset',
    method: 'POST',
    data: { email, verificationCode, newPassword },
    auth: 'none',
  })
  if (!data || typeof data.username !== 'string' || !data.username.trim()) {
    throw new Error('密码重置响应异常，请重新登录确认。')
  }
  return data.username
}
