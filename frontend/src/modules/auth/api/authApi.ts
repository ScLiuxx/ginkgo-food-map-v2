import type { ApiResponse } from '@/shared/api/api.types'
import { httpClient } from '@/shared/api/httpClient'

import type { RegistrationRequest } from '../model/registration.types'
import type { CurrentUser, LoginRequest, LoginResponse } from '../model/login.types'
import type { PasswordResetRequest } from '../model/passwordReset.types'

/** 未注册邮箱也返回相同受理结果，页面不得据此推断账号是否存在。 */
export async function sendPasswordResetCode(email: string): Promise<void> {
  await httpClient.post<ApiResponse<null>>('/auth/email/code/password-reset', { email })
}

/** 使用专用验证码设置新密码，返回已验证用户名；成功后旧凭证失效，仍须单独登录。 */
export async function resetPassword(request: PasswordResetRequest): Promise<{ username: string }> {
  const response = await httpClient.post<ApiResponse<{ username: string }>>(
    '/auth/password-reset',
    request,
  )
  return response.data.data
}

/** 请求发送注册验证码；是否允许发送及频率限制最终由服务端决定。 */
export async function sendRegistrationCode(email: string): Promise<void> {
  await httpClient.post<ApiResponse<null>>('/auth/email/code/register', { email })
}

/** 邮箱验证注册并建立登录会话，返回现有登录响应结构。 */
export async function register(request: RegistrationRequest): Promise<LoginResponse> {
  const response = await httpClient.post<ApiResponse<LoginResponse>>(
    '/auth/register-and-login',
    request,
  )
  return response.data.data
}

/** 登录成功才返回访问凭证，失败提示沿用统一 API 错误格式。 */
export async function login(request: LoginRequest): Promise<LoginResponse> {
  const response = await httpClient.post<ApiResponse<LoginResponse>>('/auth/login', request)
  return response.data.data
}

/** 显式绑定请求发起时的令牌，避免异步恢复状态时串用新会话。 */
export async function fetchCurrentUser(token: string): Promise<CurrentUser> {
  const response = await httpClient.get<ApiResponse<CurrentUser>>('/auth/me', {
    headers: { Authorization: `Bearer ${token}` },
  })
  return response.data.data
}

/** 服务端确认撤销后才能视为退出成功。 */
export async function logout(token: string): Promise<void> {
  await httpClient.post('/auth/logout', null, { headers: { Authorization: `Bearer ${token}` } })
}
