<script setup lang="ts">
import { computed, useAttrs } from 'vue'
import { useLink, type RouteLocationRaw } from 'vue-router'

defineOptions({ inheritAttrs: false })
const props = withDefaults(
  defineProps<{
    to: RouteLocationRaw
    replace?: boolean
    activeClass?: string
    exactActiveClass?: string
    ariaCurrentValue?: 'page' | 'step' | 'location' | 'date' | 'time' | 'true' | 'false'
  }>(),
  {
    replace: false,
    activeClass: 'router-link-active',
    exactActiveClass: 'router-link-exact-active',
    ariaCurrentValue: 'page',
  },
)
const attrs = useAttrs()
const { navigate, isActive, isExactActive } = useLink(props)
const classes = computed(() => ({
  [props.activeClass]: isActive.value,
  [props.exactActiveClass]: isExactActive.value,
}))
function activate(event: globalThis.MouseEvent) {
  if (attrs['aria-disabled'] === true || attrs['aria-disabled'] === 'true') {
    event.preventDefault()
    return
  }
  void navigate(event)
}
// 使用click复用来源返回等捕获阶段逻辑；没有href时浏览器不会为Enter合成点击。
function activateWithKeyboard(event: globalThis.KeyboardEvent) {
  event.preventDefault()
  if (!event.repeat) (event.currentTarget as globalThis.HTMLElement).click()
}
</script>

<template>
  <!-- 保留链接语义、原有a选择器及路由规则，不输出触发浏览器网址预览的href。 -->
  <a
    v-bind="attrs"
    role="link"
    tabindex="0"
    :class="classes"
    :aria-current="isExactActive ? ariaCurrentValue : undefined"
    @click="activate"
    @keydown.enter="activateWithKeyboard"
    ><slot
  /></a>
</template>

<style scoped>
a {
  cursor: pointer;
}
a[aria-disabled='true'] {
  cursor: not-allowed;
}
</style>
