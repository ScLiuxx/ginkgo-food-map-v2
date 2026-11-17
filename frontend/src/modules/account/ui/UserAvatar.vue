<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { avatarVersions } from '../model/avatar'

/** 统一头像展示，只构造本站地址；无图、读取失败及演示内容保留用户名首字。 */
const props = defineProps<{ userId?: string | null; username?: string | null; preview?: boolean }>()
const failed = ref(false)
const loaded = ref(false)
const source = computed(() =>
  !props.preview && props.userId && /^[1-9]\d{0,18}$/.test(props.userId)
    ? `/api/users/${props.userId}/avatar?v=${avatarVersions[props.userId] ?? 0}`
    : '',
)
const initial = computed(() => Array.from(props.username?.trim() || '同')[0]?.toUpperCase())
function imageFailed() {
  failed.value = true
  loaded.value = false
}
watch(source, () => {
  failed.value = loaded.value = false
})
</script>

<template>
  <span class="user-avatar" aria-hidden="true">
    <span v-if="!loaded">{{ initial }}</span>
    <img
      v-if="source && !failed"
      :key="source"
      :class="{ 'avatar-pending': !loaded }"
      :src="source"
      alt=""
      loading="lazy"
      decoding="async"
      @load="loaded = true"
      @error="imageFailed"
    />
  </span>
</template>

<style scoped>
.user-avatar {
  position: relative;
  display: inline-grid;
  place-items: center;
  overflow: hidden;
  width: 40px;
  height: 40px;
  flex-shrink: 0;
  border-radius: 50%;
  background: #fff1df;
  color: #b96b16;
}
.user-avatar img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.user-avatar .avatar-pending {
  opacity: 0;
}
</style>
