<script setup lang="ts">
import type { MessageIconName } from '../model/messageCategories'

// 消息域内复用同一组线条图标，与现有底部导航保持圆角、细线的视觉语言。
defineProps<{ name: MessageIconName }>()
</script>

<template>
  <svg viewBox="0 0 32 32" fill="none" aria-hidden="true">
    <g v-if="name === 'like'">
      <path d="M11 14 15 5c.5-1.2 3-.7 3 1v6h6a3 3 0 0 1 3 3.6l-2 10A3 3 0 0 1 22 28H11Z" />
      <path d="M5 14h6v14H5z" />
    </g>
    <g v-else-if="name === 'comment'">
      <path
        d="M6 5h20a2 2 0 0 1 2 2v15a2 2 0 0 1-2 2h-8l-7 5v-5H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2Z"
      />
      <path d="M10 12h12M10 17h8" />
    </g>
    <g v-else-if="name === 'follow'">
      <circle cx="12" cy="10" r="5" />
      <path d="M3 27v-3a9 9 0 0 1 15-6.7M24 13v12M18 19h12" />
    </g>
    <g v-else-if="name === 'announcement'">
      <path d="m5 14 13-4 8-6v24l-8-6-13-3ZM9 21l3 8h5l-3-7M18 10v12M29 12l2-1M29 21l2 1" />
    </g>
    <g v-else>
      <path d="M4 18 8 6h16l4 12v10H4ZM4 18h7l2 4h6l2-4h7M12 11h8M12 15h8" />
    </g>
  </svg>
</template>

<style scoped>
svg {
  width: 32px;
  height: 32px;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}
</style>
