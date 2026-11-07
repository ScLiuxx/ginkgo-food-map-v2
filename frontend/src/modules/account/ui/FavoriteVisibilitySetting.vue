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
  <div class="favorite-privacy">
    <label
      ><span>向其他登录用户公开收藏列表</span
      ><input
        type="checkbox"
        :checked="value"
        :disabled="!known || loading || saving"
        @change="change"
    /></label>
    <p>默认仅自己可见。公开后，他人也看不到失效店铺；游客始终不可查看。</p>
    <p v-if="loading || saving" role="status">{{ saving ? '正在保存…' : '正在读取收藏设置…' }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <div v-if="error" role="alert">
      <p>{{ error }}</p>
      <button type="button" @click="load">重新读取设置</button>
    </div>
  </div>
</template>
<style scoped>
.favorite-privacy {
  padding: 16px 0;
}
.favorite-privacy label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  line-height: 1.6;
}
.favorite-privacy input {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
  accent-color: #c98f2b;
}
.favorite-privacy p {
  font-size: 12px;
  color: #7c8794;
  line-height: 1.8;
}
.favorite-privacy button {
  min-height: 44px;
  padding: 6px 12px;
  border: 1px solid #ded7c8;
  border-radius: 6px;
  background: white;
}
</style>
