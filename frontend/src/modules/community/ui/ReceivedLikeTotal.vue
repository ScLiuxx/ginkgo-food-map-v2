<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { useLoginStore } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'
import { httpClient } from '@/shared/api/httpClient'
import type { ApiResponse } from '@/shared/api/api.types'
/** 本人获赞使用当前有效内容合计，失败显示未知并允许重试，不伪装为零。 */
const auth = useLoginStore(),
  count = ref<number | null>(null),
  error = ref(false),
  busy = ref(false)
let version = 0,
  controller: globalThis.AbortController | undefined
function clear() {
  version++
  controller?.abort()
  count.value = null
  error.value = false
  busy.value = false
}
async function load() {
  clear()
  if (!auth.authenticated || !authToken.value) return
  const ticket = version,
    user = String(auth.user?.id)
  controller = new globalThis.AbortController()
  busy.value = true
  try {
    const response = await httpClient.get<ApiResponse<{ userId: string; count: number }>>(
        '/api/me/received-likes',
        { signal: controller.signal, headers: { Authorization: `Bearer ${authToken.value}` } },
      ),
      data = response.data.data
    if (
      !response.data.success ||
      data?.userId !== user ||
      !Number.isSafeInteger(data.count) ||
      data.count < 0
    )
      throw new Error('获赞数无法确认')
    if (ticket === version) count.value = data.count
  } catch {
    if (ticket === version) error.value = true
  } finally {
    if (ticket === version) busy.value = false
  }
}
watch(
  () => [auth.authenticated, auth.user?.id, authToken.value],
  () => {
    void load()
  },
  { immediate: true, flush: 'sync' },
)
onBeforeUnmount(clear)
</script>
<template>
  <span class="received-like-total"
    ><strong>{{ count ?? '—' }}</strong
    ><span>获赞</span
    ><button v-if="error" :disabled="busy" @click="load">重试获赞统计</button></span
  >
</template>
<style scoped>
.received-like-total {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}
.received-like-total button {
  border: 0;
  background: none;
  font-size: 11px;
  color: #a24838;
  cursor: pointer;
}
</style>
