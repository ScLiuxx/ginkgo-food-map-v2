// 只在当前标签页内存中短暂传递已验证的用户名，不把密码或用户名放进URL/持久存储。
let resetUsername: string | null = null

export function prepareResetLogin(username: string) {
  resetUsername = username
}

export function consumeResetLogin(): string | null {
  const username = resetUsername
  resetUsername = null
  return username
}
