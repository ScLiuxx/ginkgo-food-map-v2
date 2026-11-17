<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useLoginStore } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { favoriteState, setFavorite } from '../api/favoriteApi'
const props = defineProps<{
  restaurantId: string
  /** 店铺标题只展示星形；读写状态仍通过无障碍名称、禁用及错误重试完整表达。 */
  iconOnly?: boolean
}>()
const auth = useLoginStore(),
  route = useRoute(),
  router = useRouter()
const known = ref(false),
  active = ref(false),
  loading = ref(false),
  busy = ref(false),
  error = ref('')
let controller: globalThis.AbortController | undefined,
  generation = 0
const label = computed(() => {
  if (loading.value) return '读取收藏中…'
  if (busy.value) return '正在保存…'
  if (auth.authenticated && !known.value) return '重新读取收藏状态'
  if (known.value && active.value) return props.iconOnly ? '取消收藏' : '★ 已收藏'
  return props.iconOnly ? '收藏' : '☆ 收藏'
})
/** 收藏成功由按钮状态体现；仅失败时展示说明，换店和换号清空旧状态。 */
async function load() {
  const ticket = ++generation
  controller?.abort()
  known.value = false
  active.value = false
  error.value = ''
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
  try {
    const result = await setFavorite(id, !active.value, token)
    if (ticket === generation) {
      active.value = result.favorited
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
  <div class="favorite-control" :class="{ 'icon-only': iconOnly }">
    <button
      type="button"
      class="favorite-entry"
      :aria-label="iconOnly ? label : undefined"
      :title="iconOnly ? label : undefined"
      :aria-busy="loading || busy"
      :aria-pressed="known ? active : undefined"
      :disabled="loading || busy"
      @click="toggle"
    >
      <svg v-if="iconOnly" viewBox="0 0 24 24" aria-hidden="true">
        <path d="m12 3 2.8 5.7 6.3.9-4.6 4.4 1.1 6.2-5.6-2.9-5.6 2.9 1.1-6.2L3 9.6l6.2-.9Z" />
      </svg>
      <template v-else>{{ label }}</template>
    </button>
    <p v-if="error" role="alert">
      {{ error }}
      <button
        v-if="iconOnly"
        type="button"
        class="favorite-retry"
        :disabled="loading || busy"
        @click="load"
      >
        重试读取
      </button>
    </p>
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
/* 标题行不因错误说明挤窄店名；失败说明贴近星形，重试只读取状态，不重复写入。 */
.favorite-control.icon-only {
  position: relative;
  flex-shrink: 0;
  margin: -5px 0 0;
}
.icon-only .favorite-entry {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  padding: 0;
  border: 0;
  background: transparent;
  color: #e79947;
}
.icon-only .favorite-entry svg {
  width: 23px;
  height: 23px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linejoin: round;
}
.icon-only .favorite-entry[aria-pressed='true'] svg {
  fill: #f2a447;
}
.icon-only p {
  position: absolute;
  z-index: 3;
  top: 100%;
  right: 0;
  width: min(260px, calc(100vw - 80px));
  margin: 3px 0 0;
  border: 1px solid #e8ebef;
  border-radius: 8px;
  padding: 12px;
  background: white;
  box-shadow: 0 6px 20px #26354514;
  color: #7c8794;
  line-height: 1.7;
}
.favorite-retry {
  display: block;
  margin-top: 6px;
  border: 0;
  padding: 4px 0;
  background: none;
  color: #b57d43;
  font: inherit;
  cursor: pointer;
}
</style>
