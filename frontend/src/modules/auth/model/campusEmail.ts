/** 注册、学号登录及找回密码共用固定学生邮箱后缀；服务端仍校验完整地址。 */
export const CAMPUS_EMAIL_SUFFIX = '@std.uestc.edu.cn'

export function campusEmail(prefix: string): string {
  return `${prefix.trim()}${CAMPUS_EMAIL_SUFFIX}`.toLowerCase()
}
