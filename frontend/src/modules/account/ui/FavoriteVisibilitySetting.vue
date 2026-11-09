<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { useLoginStore } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { favoriteVisibility } from '@/modules/restaurant/api/favoriteApi'
const auth = useLoginStore(),
  value = ref(false),
  known = ref(false),
  loading = ref(false),
  saving = ref(false),
  error = ref(''),
  notice = ref('')
let generation = 0,
  controller: globalThis.AbortController | undefined
async function load() {
  const ticket = ++generation
  controller?.abort()
  known.value = false
  value.value = false
  error.value = ''
  notice.value = ''
  loading.value = false
  saving.value = false
  const token = authToken.value,
    user = auth.user?.id
  if (!auth.authenticated || !token || !user) return
  controller = new globalThis.AbortController()
  loading.value = true
  try {
    const result = await favoriteVisibility(String(user), token, controller.signal)
    if (ticket === generation) {
      value.value = result.favoritesPublic
      known.value = true
    }
  } catch (reason) {
    if (ticket === generation) error.value = toErrorMessage(reason)
  } finally {
    if (ticket === generation) loading.value = false
  }
}
/** 显式保存目标值；DOM立即恢复旧值，成功响应才更改状态，失败先重读。 */
async function change(event: globalThis.Event) {
  const element = event.target as globalThis.HTMLInputElement,
    next = element.checked
  element.checked = value.value
  if (saving.value || !known.value) return
  const token = authToken.value,
    user = auth.user?.id,
    ticket = generation
  if (!token || !user) return
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const result = await favoriteVisibility(String(user), token, undefined, next)
    if (ticket === generation) {
      value.value = result.favoritesPublic
      notice.value = '收藏可见性已保存'
    }
  } catch (reason) {
    if (ticket === generation) {
      known.value = false
      error.value = `${toErrorMessage(reason)}。保存结果未确认，请重新读取设置。`
    }
  } finally {
    if (ticket === generation) saving.value = false
  }
}
watch(() => [auth.authenticated, auth.user?.id, authToken.value], load, {
  immediate: true,
  flush: 'sync',
})
onBeforeUnmount(() => {
  generation++
  controller?.abort()
})
</script>
<template>
  <div class="favorite-privacy privacy-setting">
    <label class="privacy-row"
      ><span class="privacy-copy"
        ><span class="privacy-label">公开收藏列表</span
        ><span class="privacy-description">允许其他登录用户查看有效店铺</span></span
      ><input
        class="privacy-switch"
        type="checkbox"
        role="switch"
        aria-label="公开收藏列表"
        :checked="value"
        :disabled="!known || loading || saving"
        @change="change"
    /></label>
    <p v-if="loading || saving" role="status">{{ saving ? '正在保存…' : '正在读取收藏设置…' }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <div v-if="error" role="alert">
      <p>{{ error }}</p>
      <button type="button" @click="load">重新读取设置</button>
    </div>
  </div>
</template>
<style scoped src="../../../shared/styles/privacy-setting.css"></style>
