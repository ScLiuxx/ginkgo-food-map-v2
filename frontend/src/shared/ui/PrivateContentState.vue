<script setup lang="ts">
defineProps<{ kind: 'favorites' | 'following' | 'followers' }>()
const labels = { favorites: '收藏', following: '关注', followers: '粉丝' }
</script>

<template>
  <div class="private-content-state" role="status">
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <rect x="5" y="10" width="14" height="11" rx="2" />
      <path d="M8 10V7a4 4 0 0 1 8 0v3M12 14v3" />
    </svg>
    <p>对方未公开{{ labels[kind] }}</p>
  </div>
</template>

<style scoped>
.private-content-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  padding: 64px 16px 72px;
  text-align: center;
}
.private-content-state svg {
  width: 28px;
  height: 28px;
  fill: none;
  stroke: #a4adb7;
  stroke-width: 1.5;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.private-content-state p {
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  color: #8d97a3;
}
</style>
