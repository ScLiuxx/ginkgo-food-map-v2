import { httpClient } from '@/shared/api/httpClient'
import type { ApiResponse } from '@/shared/api/api.types'

/** 挂载工作台前由后端验证管理权限；绑定发起时凭证，旧请求不能借用切换后的会话。 */
export async function verifyAdminSession(token: string): Promise<void> {
  const response = await httpClient.get<ApiResponse<{ roles: string[] }>>('/api/admin/me', {
    headers: { Authorization: `Bearer ${token}` },
  })
  // 再核对返回身份，角色在认证与身份查询之间变化时也不能展示工作台。
  if (!response.data.data.roles.includes('ROLE_ADMIN'))
    throw new Error('当前账号没有管理权限，请退出后重新登录')
}
