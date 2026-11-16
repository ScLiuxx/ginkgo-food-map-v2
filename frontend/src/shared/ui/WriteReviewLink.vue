<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { reviewDestination } from '@/shared/lib/reviewNavigation'

const route = useRoute()
const destination = computed(() => reviewDestination(route))
</script>

<template>
  <!-- 临时打开意图替换当前条目，消费后地址恢复原样；关闭时无需猜测返回目标。 -->
  <RouterLink :to="destination" replace><slot /></RouterLink>
</template>
