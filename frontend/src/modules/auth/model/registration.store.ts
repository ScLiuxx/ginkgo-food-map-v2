import { defineStore } from 'pinia'
import { ref } from 'vue'

import { toErrorMessage } from '@/shared/lib/toErrorMessage'

import { register, sendRegistrationCode } from '../api/authApi'
import type { RegistrationRequest } from './registration.types'
import { getAccessToken } from '@/shared/api/authSession'
import { useLoginStore } from './login.store'

/**
 * 管理注册页面的远程请求状态。
 *
 * 密码和验证码不进入全局 Store，避免敏感输入在页面离开后继续驻留；Store 只保存加载、错误和成功状态。
 */
export const useRegistrationStore = defineStore('registration', () => {
  const sendingCode = ref(false)
  const registering = ref(false)
  const registered = ref(false)
  const error = ref<string | null>(null)

  /** 发送验证码并用布尔值告知表单是否可以启动倒计时。 */
  async function sendCode(email: string): Promise<boolean> {
    sendingCode.value = true
    error.value = null
    try {
      await sendRegistrationCode(email)
      return true
    } catch (reason) {
      error.value = toErrorMessage(reason)
      return false
    } finally {
      sendingCode.value = false
    }
  }

  /** 提交注册；失败时只保存可展示的安全提示，不保存请求内容。 */
  async function submit(request: RegistrationRequest): Promise<boolean> {
    if (registering.value || registered.value) return false
    const previousToken = getAccessToken()
    registering.value = true
    registered.value = false
    error.value = null
    try {
      const session = await register(request)
      // 请求期间在其他标签页发生的新登录优先，不能被旧请求覆盖。
      if (getAccessToken() === previousToken) useLoginStore().acceptSession(session)
      registered.value = true
      return true
    } catch (reason) {
      error.value = toErrorMessage(reason)
      return false
    } finally {
      registering.value = false
    }
  }

  function clearError() {
    error.value = null
  }

  /** 每次重新进入注册页时清除上一次页面会话的结果，不保留跨页面注册状态。 */
  function reset() {
    sendingCode.value = false
    registering.value = false
    registered.value = false
    error.value = null
  }

  return { sendingCode, registering, registered, error, sendCode, submit, clearError, reset }
})
