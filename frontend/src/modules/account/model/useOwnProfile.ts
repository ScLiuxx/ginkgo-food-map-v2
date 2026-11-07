import { onBeforeUnmount, ref, watch } from 'vue'
import { useLoginStore } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { fetchOwnProfile, updateOwnBio, type OwnProfile } from '../api/profileApi'
import { bioValidationError, normalizeBio } from './profileValidation'

/** 页面级本人资料；GET与PATCH都固定会话，旧回执不能恢复已退出账号或覆盖刚保存的简介。 */
export function useOwnProfile(enabled: () => boolean) {
  const auth = useLoginStore()
  const profile = ref<OwnProfile | null>(null)
  const loading = ref(false),
    saving = ref(false),
    error = ref(''),
    saveError = ref('')
  let readController: AbortController | undefined, writeController: AbortController | undefined
  let generation = 0,
    context = 0

  async function refresh() {
    if (saving.value) return
    const userId = auth.user ? String(auth.user.id) : null,
      token = authToken.value
    if (!enabled() || !auth.authenticated || !userId || !token) return
    const ticket = ++generation,
      owner = context
    readController?.abort()
    readController = new AbortController()
    profile.value = null
    loading.value = true
    error.value = ''
    try {
      const result = await fetchOwnProfile(userId, token, readController.signal)
      if (ticket === generation && owner === context) profile.value = result
    } catch (reason) {
      if (ticket === generation && owner === context) error.value = toErrorMessage(reason)
    } finally {
      if (ticket === generation && owner === context) loading.value = false
    }
  }
  async function save(input: string): Promise<boolean> {
    const userId = auth.user ? String(auth.user.id) : null,
      token = authToken.value
    if (
      saving.value ||
      !enabled() ||
      !auth.authenticated ||
      !userId ||
      !token ||
      profile.value?.userId !== userId
    )
      return false
    saveError.value = bioValidationError(input)
    if (saveError.value) return false
    const owner = context,
      bio = normalizeBio(input)
    // 保存开始后旧GET失效，避免旧读取晚到覆盖已提交的简介。
    generation++
    readController?.abort()
    loading.value = false
    writeController = new AbortController()
    saving.value = true
    try {
      const result = await updateOwnBio(userId, token, bio, writeController.signal)
      if (owner !== context) return false
      profile.value = result
      error.value = ''
      return true
    } catch (reason) {
      if (owner === context) saveError.value = toErrorMessage(reason)
      return false
    } finally {
      if (owner === context) saving.value = false
    }
  }
  function reset() {
    context++
    generation++
    readController?.abort()
    writeController?.abort()
    profile.value = null
    loading.value = saving.value = false
    error.value = saveError.value = ''
    if (enabled() && auth.authenticated) void refresh()
  }
  watch([enabled, () => auth.authenticated, () => auth.user?.id, () => authToken.value], reset, {
    immediate: true,
    flush: 'sync',
  })
  onBeforeUnmount(() => {
    context++
    generation++
    readController?.abort()
    writeController?.abort()
  })
  return {
    profile,
    loading,
    saving,
    error,
    saveError,
    refresh,
    save,
    clearSaveError: () => {
      saveError.value = ''
    },
  }
}
