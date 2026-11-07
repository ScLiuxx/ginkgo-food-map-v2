<script setup lang="ts">
import { computed } from 'vue'
/** 用户只显示用户名；稳定用户ID来自现有作者响应，不按同名用户猜测身份。 */
const props = defineProps<{
  userId?: string
  username: string
  level?: number
  authenticated: boolean
  preview?: boolean
}>()
const destination = computed(() => {
  if (props.preview || !props.userId || !/^[1-9]\d*$/.test(props.userId)) return null
  const path = `/users/${props.userId}`
  return props.authenticated ? path : { path: '/login', query: { redirect: path } }
})
</script>
<template>
  <RouterLink v-if="destination" class="user-identity" :to="destination">{{ username }}</RouterLink
  ><strong v-else class="user-identity">{{ username }}</strong>
  <span v-if="Number.isInteger(level) && level! > 0" class="identity-level">Lv.{{ level }}</span>
</template>
<style scoped>
.identity-level {
  font-size: 12px;
  color: #a96f27;
  margin-left: 6px;
  white-space: nowrap;
}
.user-identity {
  color: inherit;
  font-weight: 600;
  text-decoration: none;
  overflow-wrap: anywhere;
}
a.user-identity:hover {
  color: #ad7e35;
}
a.user-identity:focus-visible {
  outline: 2px solid #cf9f53;
  outline-offset: 3px;
}
</style>
