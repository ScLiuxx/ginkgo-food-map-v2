<script setup lang="ts">
/** 沿用站内线性SVG图标；仅表示功能，不作为用户默认头像。 */
defineProps<{ name: string }>()
const paths: Record<string, string> = {
  review: 'M5 3h14v18H5z M8 8h8 M8 12h8 M8 16h5',
  star: 'm12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2-5.6-3-5.6 3 1.1-6.2L3 9.6l6.2-.9z',
  edit: 'M13 5H5v15h15v-8 M10 14l1-4 8-8 3 3-8 8z',
  settings:
    'm10 3-.6 2.5-2 .9L5 5.8 3 9.2l1.9 1.7v2.2L3 14.8l2 3.4 2.4-.6 2 .9L10 21h4l.6-2.5 2-.9 2.4.6 2-3.4-1.9-1.7v-2.2L21 9.2l-2-3.4-2.4.6-2-.9L14 3Z M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0',
  growth: 'M4 19h16 M6 15l4-5 4 2 5-8 M15 4h4v4',
  store:
    'M4 10v11h16V10 M3 10l2-7h14l2 7 M3 10c0 3 4 3 4 0 0 3 5 3 5 0 0 3 5 3 5 0 0 3 4 3 4 0 M9 21v-7h6v7',
  heart:
    'M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.6a5.5 5.5 0 0 0 0-7.8Z',
  shield: 'm12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6z M8 12l3 3 5-6',
  people:
    'M10 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8 M3 21v-3a7 7 0 0 1 14 0v3 M18 4a4 4 0 0 1 0 7 M20 15a5 5 0 0 1 2 4v2',
}
</script>
<template>
  <svg viewBox="0 0 24 24" aria-hidden="true"><path :d="paths[name] || paths.review" /></svg>
</template>
<style scoped>
svg {
  width: 24px;
  height: 24px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}
</style>
