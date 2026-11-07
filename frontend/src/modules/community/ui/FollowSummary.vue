<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { useLoginStore } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { fetchFollowSummary, setFollow, type FollowSummary } from '../api/followsApi'
const props = defineProps<{ userId: string }>(),
  auth = useLoginStore()
const own = computed(() => props.userId === String(auth.user?.id))
const data = ref<FollowSummary | null>(null),
  loading = ref(false),
  busy = ref(false),
  known = ref(false),
  error = ref('')
let generation = 0,
  controller: globalThis.AbortController | undefined
/** 读取及写入绑定当前查看对象与会话，未知结果必须重读而非盲目翻转。 */
async function load() {
  const ticket = ++generation
  controller?.abort()
  data.value = null
  known.value = false
  error.value = ''
  loading.value = false
  busy.value = false
  const token = authToken.value
  if (!auth.authenticated || !token) return
  controller = new globalThis.AbortController()
  loading.value = true
  try {
    const result = await fetchFollowSummary(props.userId, token, controller.signal)
    if (ticket === generation) {
      data.value = result
      known.value = true
    }
  } catch (reason) {
    if (ticket === generation) error.value = toErrorMessage(reason)
  } finally {
    if (ticket === generation) loading.value = false
  }
}
async function toggle() {
  if (own.value || busy.value || loading.value) return
  if (!known.value || !data.value) {
    await load()
    return
  }
  const ticket = generation,
    token = authToken.value
  if (!token) return
  busy.value = true
  error.value = ''
  try {
    const result = await setFollow(props.userId, !data.value.following, token)
    if (ticket === generation) data.value = result
  } catch (reason) {
    if (ticket === generation) {
      known.value = false
      error.value = `${toErrorMessage(reason)}。结果未确认，请重新读取。`
    }
  } finally {
    if (ticket === generation) busy.value = false
  }
}
const path = (kind: string) => (own.value ? `/account/${kind}` : `/users/${props.userId}/${kind}`)
watch(() => [props.userId, auth.authenticated, auth.user?.id, authToken.value], load, {
  immediate: true,
  flush: 'sync',
})
onBeforeUnmount(() => {
  generation++
  controller?.abort()
})
</script>
<template>
  <div class="follow-summary" :aria-busy="loading">
    <div class="follow-counts">
      <RouterLink :to="path('following')"
        ><strong>{{ data?.followingCount ?? '—' }}</strong> 关注</RouterLink
      ><RouterLink :to="path('followers')"
        ><strong>{{ data?.followersCount ?? '—' }}</strong> 粉丝</RouterLink
      >
    </div>
    <button
      v-if="!own"
      type="button"
      :disabled="loading || busy"
      :aria-pressed="known ? data?.following : undefined"
      @click="toggle"
    >
      {{
        loading
          ? '读取关注中…'
          : busy
            ? '正在保存…'
            : !known
              ? '重新读取关注状态'
              : data?.following
                ? '已关注 · 取消'
                : '关注'
      }}
    </button>
    <p v-if="error" role="alert">{{ error }} <button v-if="own" @click="load">重新读取</button></p>
  </div>
</template>
<style scoped>
.follow-summary {
  margin: 16px 0;
  max-width: 100%;
}
.follow-counts {
  display: flex;
  gap: 24px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.follow-counts a {
  color: #77808d;
  font-size: 14px;
  text-decoration: none;
}
.follow-counts strong {
  color: #374151;
}
.follow-summary button {
  min-height: 44px;
  padding: 7px 16px;
  border: 1px solid #e3d0aa;
  border-radius: 6px;
  background: #fff8e8;
  color: #976820;
  cursor: pointer;
}
.follow-summary button:disabled {
  opacity: 0.5;
}
.follow-summary p {
  font-size: 12px;
  overflow-wrap: anywhere;
  color: #9b6b35;
}
</style>
