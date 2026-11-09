/** 公开账号信息与后端登录响应保持一致，不包含内部凭证版本或密码。 */
export interface CurrentUser {
  id: number
  username: string
  roles: string[]
}

export interface LoginRequest {
  /** 兼容接口字段名，值可以是用户名或注册邮箱。 */
  username: string
  password: string
}

export interface LoginResponse {
  accessToken: string
  tokenType: 'Bearer'
  expiresAt: string
  user: CurrentUser
}
