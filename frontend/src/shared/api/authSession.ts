import { readonly, shallowRef } from 'vue'

const storageKey = 'foodmap.access-token'

/** 清除旧版标签页凭证，避免退出后再次从 sessionStorage 恢复已经失效的登录。 */
function clearLegacyToken(): void {
  try {
    sessionStorage.removeItem(storageKey)
  } catch {
    // 浏览器禁用存储时，仅使用当前页面的内存凭证。
  }
}

/** 优先恢复持久凭证；旧版标签页凭证只迁移一次，有效性仍由服务端确认。 */
function readToken(): string | null {
  try {
    const saved = localStorage.getItem(storageKey)
    if (saved) {
      clearLegacyToken()
      return saved
    }
  } catch {
    // 持久存储不可用时仍尝试读取旧版标签页凭证，保持升级兼容性。
  }
  let legacy: string | null = null
  try {
    legacy = sessionStorage.getItem(storageKey)
    if (legacy) localStorage.setItem(storageKey, legacy)
  } catch {
    // 即使迁移写入被拒绝，已读取的凭证仍可用于本次内存会话。
  }
  clearLegacyToken()
  return legacy
}

const token = shallowRef<string | null>(readToken())
export const authToken = readonly(token)

/** HTTP 层读取凭证，密码始终由表单局部状态管理。 */
export function getAccessToken(): string | null {
  return token.value
}

/** 默认持久保存 JWT；不保存密码，不修改 JWT 或 Redis 会话的服务端有效期。 */
export function setAccessToken(value: string): void {
  token.value = value
  try {
    localStorage.setItem(storageKey, value)
  } catch {
    // 存储不可用不影响本次内存会话。
  }
  clearLegacyToken()
}

/** 只清除产生失败请求的凭证，防止旧请求的401覆盖刚完成的新登录。 */
export function clearAccessToken(expectedToken?: string): void {
  if (expectedToken !== undefined && token.value !== expectedToken) return
  try {
    const saved = localStorage.getItem(storageKey)
    // 另一个标签页的新登录可能已经写入存储，但 storage 事件尚未送达。
    // 此时旧请求的401或退出响应只同步最新凭证，不能删除另一个标签页的新登录。
    if (expectedToken !== undefined && saved && saved !== expectedToken) {
      token.value = saved
      return
    }
    localStorage.removeItem(storageKey)
  } catch {
    // 存储被浏览器禁用时仍清除本页面内存，服务端会话撤销不依赖浏览器存储。
  }
  token.value = null
  clearLegacyToken()
}

/** 同源标签页共享当前登录；重读实际存储，避免迟到的 storage 事件恢复陈旧凭证。 */
window.addEventListener('storage', (event) => {
  if (event.key !== storageKey && event.key !== null) return
  try {
    if (event.storageArea !== null && event.storageArea !== localStorage) return
    token.value = localStorage.getItem(storageKey)
  } catch {
    // 浏览器临时拒绝访问存储时保持当前内存会话，由后端继续判断凭证是否有效。
  }
})
