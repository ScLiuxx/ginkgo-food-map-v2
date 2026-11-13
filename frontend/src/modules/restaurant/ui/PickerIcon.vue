<script setup lang="ts">
/** 选店弹窗使用固定内联线性图标，不依赖外部图标请求或不受信任SVG。 */
withDefaults(defineProps<{ name: keyof typeof paths; size?: number }>(), { size: 17 })
const paths = {
  shuffle: 'M3 6h3c5 0 7 12 12 12h3m-4-4 4 4-4 4M3 18h3c2 0 4-3 6-6m2-4c1-1 2-2 4-2h3m-4-4 4 4-4 4',
  close: 'm6 6 12 12M6 18 18 6',
  campus: 'M3 21h18M5 21V9l7-5 7 5v12M9 21v-6h6v6M9 10h.01M15 10h.01',
  locate:
    'M12 2v3m0 14v3M2 12h3m14 0h3M19 12a7 7 0 1 1-14 0 7 7 0 0 1 14 0Zm-4 0a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z',
  bowl: 'M3 10h18c-1 6-4 9-9 9s-8-3-9-9ZM7 22h10M8 3v3m4-4v4m4-3v3',
  bag: 'M5 7h14l1 14H4L5 7Zm3 0V5a4 4 0 0 1 8 0v2',
  arrow: 'M4 12h16m-6-6 6 6-6 6',
  left: 'm14 6-6 6 6 6',
  edit: 'm15 4 5 5M4 20l5-1L21 7l-5-5L4 14v6Z',
  check: 'm5 12 4 4L19 6',
  wallet: 'M20 7H5a2 2 0 0 1 0-4h13v4m2 0v14H5a2 2 0 0 1-2-2V5m17 7h-6v5h6m-3-2.5h.01',
  pin: 'M19 10c0 5-7 11-7 11S5 15 5 10a7 7 0 1 1 14 0Zm-4 0a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z',
  navigation: 'm21 3-7 18-3-8-8-3L21 3Z',
  search: 'M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0Zm-2 5 6 6',
} as const
</script>
<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.6"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path :d="paths[name]" />
  </svg>
</template>
