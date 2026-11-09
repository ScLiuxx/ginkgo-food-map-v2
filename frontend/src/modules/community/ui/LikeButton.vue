<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { fetchLike, setLike, type LikeState, type LikeType } from '../api/likesApi'
/** 真实关系状态只属于当前身份；写入失败后先重新读取，避免提交成功但响应丢失时反向操作。 */
const props = defineProps<{
  id: string
  type: LikeType
  own: boolean
  authenticated: boolean
  viewerId?: string
}>()
const route = useRoute(),
  router = useRouter()
const result = ref<LikeState | null>(null),
  busy = ref(false),
  error = ref('')
let version = 0,
  controller: globalThis.AbortController | undefined
function clear() {
  version++
  controller?.abort()
  result.value = null
  busy.value = false
  error.value = ''
}
async function load() {
  clear()
  if (props.authenticated && !props.viewerId) return
  const ticket = version
  controller = new globalThis.AbortController()
  busy.value = true
  try {
    const data = await fetchLike(
      props.type,
      props.id,
      props.authenticated ? (props.viewerId ?? null) : null,
      props.authenticated ? authToken.value : null,
      controller.signal,
    )
    if (ticket === version) result.value = data
  } catch (reason) {
    if (ticket === version) error.value = toErrorMessage(reason)
  } finally {
    if (ticket === version) busy.value = false
  }
}
async function toggle() {
  if (!props.authenticated) {
    await router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  if (props.own || busy.value || !result.value || !result.value.available || !authToken.value)
    return
  const ticket = ++version,
    desired = !result.value.liked
  controller = new globalThis.AbortController()
  busy.value = true
  error.value = ''
  try {
    const value = await setLike(props.type, props.id, desired, authToken.value, controller.signal)
    if (ticket === version) result.value = value
  } catch (reason) {
    if (ticket === version) error.value = toErrorMessage(reason)
  } finally {
    if (ticket === version) busy.value = false
  }
}
watch(
  () => [props.id, props.type, props.authenticated, props.viewerId, authToken.value],
  () => {
    void load()
  },
  { immediate: true, flush: 'sync' },
)
onBeforeUnmount(clear)
</script>
<template>
  <span class="like-control">
    <button
      class="like-entry"
      :class="{ liked: result?.liked }"
      :aria-pressed="!!result?.liked"
      :aria-label="`${result?.liked ? '取消点赞' : '点赞'}（${result?.count ?? '—'}）`"
      :disabled="busy || own || !!error || !result?.available"
      :title="own ? '不能给自己的内容点赞' : result?.liked ? '取消点赞' : '点赞'"
      @click="toggle"
    >
      <!-- 图标表达点赞状态，读屏名称仍保留操作含义与实际计数。 -->
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path
          d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.6a5.5 5.5 0 0 0 0-7.8Z"
        />
      </svg>
      <span>{{ result?.count ?? '—' }}</span>
    </button>
    <span v-if="error" class="like-error" role="alert"
      >{{ error }} <button @click="load">重新读取</button></span
    >
  </span>
</template>
<style scoped>
.like-control {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  overflow-wrap: anywhere;
}
.like-entry {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: 0;
  background: none;
  color: inherit;
  font: inherit;
  cursor: pointer;
  padding: 6px 0;
}
.like-entry svg {
  width: 16px;
  height: 16px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.like-entry.liked {
  color: #ed8424;
}
.like-entry.liked svg {
  fill: currentColor;
}
.like-entry:disabled {
  cursor: not-allowed;
}
.like-error {
  color: #a24838;
  font-size: 12px;
}
.like-error button {
  border: 0;
  background: none;
  color: inherit;
  text-decoration: underline;
}
</style>
