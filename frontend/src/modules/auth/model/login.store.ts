import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import {
  authToken,
  clearAccessToken,
  getAccessToken,
  setAccessToken,
} from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { fetchCurrentUser, login, logout } from '../api/authApi'
import type { CurrentUser, LoginRequest, LoginResponse } from './login.types'

/** 管理登录展示状态；密码不进入 Store，旧请求也不能恢复已经退出的账号。 */
export const useLoginStore = defineStore('login', () => {
  const user = ref<CurrentUser | null>(null)
  const loggingIn = ref(false)
  const restoring = ref(false)
  const loggingOut = ref(false)
  const error = ref<string | null>(null)
  const authenticated = computed(() => user.value !== null && authToken.value !== null)
  const hasSession = computed(() => authToken.value !== null)
  let revision = 0
  let restoringToken: string | null = null

  // 包括其他标签页登录在内的任何凭证变化，都先移除旧身份并丢弃在途请求结果。
  watch(
    authToken,
    () => {
      user.value = null
      revision++
      restoringToken = null
      restoring.value = false
    },
    { flush: 'sync' },
  )

  /** 提交后仅保存令牌和公开用户信息，不保留请求中的密码。 */
  function acceptSession(result: LoginResponse): void {
    // 新令牌会同步触发旧身份清理；之后再写入这个令牌对应的用户快照。
    setAccessToken(result.accessToken)
    user.value = result.user
    error.value = null
  }

  async function submit(request: LoginRequest): Promise<boolean> {
    if (loggingIn.value) return false
    const current = ++revision
    loggingIn.value = true
    error.value = null
    try {
      const result = await login(request)
      if (current !== revision) return false
      acceptSession(result)
      return true
    } catch (reason) {
      if (current === revision) error.value = toErrorMessage(reason)
      return false
    } finally {
      loggingIn.value = false
    }
  }

  /** 刷新后由服务端重新验证身份；网络故障保留凭证以供重试，401由HTTP层清理。 */
  async function restore(): Promise<void> {
    const token = getAccessToken()
    if (!token || restoringToken === token) return
    const current = ++revision
    restoringToken = token
    restoring.value = true
    error.value = null
    try {
      const result = await fetchCurrentUser(token)
      if (current === revision && getAccessToken() === token) user.value = result
    } catch (reason) {
      if (getAccessToken() === token) error.value = toErrorMessage(reason)
    } finally {
      // 凭证切换后允许立即校验新会话；旧请求完成不能关闭新请求的加载状态。
      if (restoringToken === token) {
        restoringToken = null
        restoring.value = false
      }
    }
  }

  /** 撤销当前会话，失败时保留可重试状态；异步旧退出不得清除新登录。 */
  async function signOut(): Promise<void> {
    const token = getAccessToken()
    if (!token || loggingOut.value) return
    const current = ++revision
    loggingOut.value = true
    error.value = null
    try {
      await logout(token)
      if (current === revision) clearAccessToken(token)
    } catch (reason) {
      if (getAccessToken() === token) error.value = toErrorMessage(reason)
    } finally {
      loggingOut.value = false
    }
  }

  function clearError(): void {
    error.value = null
  }

  return {
    user,
    loggingIn,
    restoring,
    loggingOut,
    error,
    authenticated,
    hasSession,
    submit,
    acceptSession,
    restore,
    signOut,
    clearError,
  }
})
