import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { authToken } from '@/shared/api/authSession'

/** 仅缓存当前账号的返回位置和回执，不持久化表单或管理资料。 */
export const useWindowManagementBrowsing = defineStore('admin-window-browsing', () => {
  const context = ref({ canteenId: '', scrollTop: 0, focusId: '', notice: '' })
  watch(authToken, () => {
    context.value = { canteenId: '', scrollTop: 0, focusId: '', notice: '' }
  })
  function enter(canteenId: string) {
    if (context.value.canteenId !== canteenId)
      context.value = { canteenId, scrollTop: 0, focusId: '', notice: '' }
    return context.value
  }
  return { context, enter }
})
