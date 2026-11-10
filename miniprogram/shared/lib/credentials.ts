/** 账号相关前端校验；服务端仍是最终校验来源，这里只做即时反馈。 */
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const USERNAME_PATTERN = /^[\u4e00-\u9fa5A-Za-z0-9_]{2,24}$/
const PASSWORD_PATTERN = /^[\x21-\x7e]{4,32}$/

export function isValidEmail(value: string): boolean {
  const email = value.trim()
  return email.length > 0 && email.length <= 254 && EMAIL_PATTERN.test(email)
}

export function isValidUsername(value: string): boolean {
  return USERNAME_PATTERN.test(value.trim())
}

/** 密码允许 ASCII 字母、数字和英文符号，4–32 位，不含空格、中文或控制字符。 */
export function isValidPassword(value: string): boolean {
  return PASSWORD_PATTERN.test(value)
}

export const USERNAME_HINT = '2–24 个字符，可使用中文、英文字母、数字和下划线'
export const PASSWORD_HINT = '4–32 位英文字母、数字或英文符号，不能包含空格'
export const EMAIL_HINT = '需使用学校学生邮箱 @std.uestc.edu.cn'
