import { defineStore } from 'pinia'
import { ref } from 'vue'
import { useMyLocation } from './location'
import { loadAMap } from '../sdk/amap'
import type { AMapSDK } from '../sdk/types'

/** 用户主动触发一次定位，浏览器WGS-84通过现有高德转换为GCJ-02；仅保留会话内存。 */
export const useUserLocation = defineStore('user-location', () => {
  let sdk: AMapSDK | undefined
  const state = useMyLocation(() => sdk)
  const preparing = ref(false)
  async function locate() {
    if (preparing.value || state.locating.value) return
    preparing.value = true
    try {
      sdk = await loadAMap()
      state.locate()
    } catch {
      state.message.value = '定位服务暂不可用，可继续浏览餐厅，稍后重试。'
    } finally {
      preparing.value = false
    }
  }
  return { ...state, preparing, locate }
})
