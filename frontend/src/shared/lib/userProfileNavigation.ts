/** 本人资料统一进入个人中心；访问者ID来自已认证身份，不按昵称判断是否本人。 */
export function userProfilePath(userId: string, viewerId?: string | number | null): string {
  return viewerId != null && userId === String(viewerId) ? '/account' : `/users/${userId}`
}
