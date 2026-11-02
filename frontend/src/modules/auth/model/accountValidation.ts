/** 新账号输入规则；登录只校验必填，避免阻止旧账号使用历史密码。 */
export function usernameError(value: string): string | null {
  const name = value.trim()
  const length = Array.from(name).length
  if (!name) return '请输入用户名'
  if (length < 2) return '用户名至少需要2位'
  if (length > 24) return '用户名不能超过24位'
  if (!/^[\p{Script=Han}a-zA-Z0-9_]+$/u.test(name)) {
    return '用户名仅支持中文、英文字母、数字和下划线，不允许空格'
  }
  return null
}

export function passwordError(value: string): string | null {
  if (!value) return '请输入密码'
  if (value.length < 4) return '密码至少需要4位'
  if (value.length > 32) return '密码不能超过32位'
  if (!/^[\x21-\x7e]+$/.test(value)) return '密码仅支持英文字母、数字和英文符号，不允许中文或空格'
  return null
}

export function emailError(value: string): string | null {
  return /^[^\s@]+@std\.uestc\.edu\.cn$/i.test(value.trim())
    ? null
    : '请输入正确的 @std.uestc.edu.cn 学生邮箱'
}

export function codeError(value: string): string | null {
  return /^[0-9]{6}$/.test(value) ? null : '请输入邮件中的6位数字验证码'
}

/** 服务端明确指出字段时就近展示；未知错误保留在表单底部，不推断账号是否存在。 */
export function accountErrorField(message: string | null): string | null {
  if (!message) return null
  if (/验证码|验证代码/.test(message)) return 'code'
  if (/用户名/.test(message) && !/密码|邮箱/.test(message)) return 'username'
  if (/邮箱/.test(message) && !/用户名/.test(message)) return 'email'
  if (/密码/.test(message) && !/用户名/.test(message)) return 'password'
  return null
}
