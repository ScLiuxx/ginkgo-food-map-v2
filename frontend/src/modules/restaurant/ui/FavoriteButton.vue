<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useLoginStore } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { favoriteState, setFavorite } from '../api/favoriteApi'
const props = defineProps<{ restaurantId: string }>()
const auth = useLoginStore(),
  route = useRoute(),
  router = useRouter()
const known = ref(false),
  active = ref(false),
  loading = ref(false),
  busy = ref(false),
  error = ref(''),
  notice = ref('')
let controller: globalThis.AbortController | undefined,
  generation = 0
/** 换店和换号都清空旧状态；写入失败先重读服务端，不依赖乐观翻转。 */
async function load() {
  const ticket = ++generation
  controller?.abort()
  known.value = false
  active.value = false
  error.value = ''
  notice.value = ''
  loading.value = false
  busy.value = false
  const token = authToken.value
  if (!auth.authenticated || !token) return
  controller = new globalThis.AbortController()
  loading.value = true
  try {
    const result = await favoriteState(props.restaurantId, token, controller.signal)
    if (ticket === generation) {
      active.value = result.favorited
      known.value = true
    }
  } catch (reason) {
    if (ticket === generation) error.value = toErrorMessage(reason)
  } finally {
    if (ticket === generation) loading.value = false
  }
}
async function toggle() {
  if (!auth.authenticated) {
    await router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  if (busy.value || loading.value) return
  if (!known.value) {
    await load()
    return
  }
  const ticket = generation,
    token = authToken.value,
    id = props.restaurantId
  if (!token) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const result = await setFavorite(id, !active.value, token)
    if (ticket === generation) {
      active.value = result.favorited
      notice.value = result.favorited ? '已收藏，可在“我的收藏”查看' : '已取消收藏'
    }
  } catch (reason) {
    if (ticket === generation) {
      known.value = false
      error.value = `${toErrorMessage(reason)}。结果未确认，请重新读取收藏状态。`
    }
  } finally {
    if (ticket === generation) busy.value = false
  }
}
watch(() => [props.restaurantId, auth.authenticated, auth.user?.id, authToken.value], load, {
  immediate: true,
  flush: 'sync',
})
onBeforeUnmount(() => {
  generation++
  controller?.abort()
})
</script>
<template>
  <div class="favorite-control">
    <button
      type="button"
      class="favorite-entry"
      :aria-pressed="known ? active : undefined"
      :disabled="loading || busy"
      @click="toggle"
    >
      {{
        loading
          ? '读取收藏中…'
          : busy
            ? '正在保存…'
            : !auth.authenticated
              ? '☆ 收藏'
              : !known
                ? '重新读取收藏状态'
                : active
                  ? '★ 已收藏'
                  : '☆ 收藏'
      }}
    </button>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-else-if="notice" role="status">{{ notice }}</p>
  </div>
</template>
<style scoped>
.favorite-control {
  display: inline-block;
  max-width: 100%;
  margin-top: 12px;
}
.favorite-entry {
  min-height: 44px;
  padding: 8px 16px;
  border: 1px solid #e4d3ad;
  border-radius: 6px;
  background: #fffaf0;
  color: #986c26;
  cursor: pointer;
}
.favorite-entry[aria-pressed='true'] {
  background: #fff0d4;
}
.favorite-control p {
  font-size: 12px;
  color: #87683b;
  overflow-wrap: anywhere;
}
.favorite-entry:disabled {
  opacity: 0.6;
  cursor: wait;
}
</style>
