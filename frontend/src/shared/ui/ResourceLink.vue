<script setup lang="ts">
defineOptions({ inheritAttrs: false })
const props = withDefaults(defineProps<{ href: string; target?: '_blank' | '_self' }>(), {
  target: '_self',
})
/** 外部导航、版权及原图仍打开原目标；不向DOM输出href，避免浏览器网址预览。 */
function open(event: globalThis.MouseEvent) {
  if (event.defaultPrevented || event.button !== 0) return
  event.preventDefault()
  const url = new globalThis.URL(props.href, globalThis.location.href)
  if (!['http:', 'https:', 'blob:'].includes(url.protocol)) return
  if (props.target === '_blank') globalThis.open(url.href, '_blank', 'noopener,noreferrer')
  else globalThis.location.assign(url.href)
}
function openWithKeyboard(event: globalThis.KeyboardEvent) {
  event.preventDefault()
  if (!event.repeat) (event.currentTarget as globalThis.HTMLElement).click()
}
</script>

<template>
  <a
    v-bind="$attrs"
    role="link"
    tabindex="0"
    :target="target"
    @click="open"
    @keydown.enter="openWithKeyboard"
    ><slot
  /></a>
</template>

<style scoped>
a {
  cursor: pointer;
}
</style>
