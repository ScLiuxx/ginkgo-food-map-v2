<script setup lang="ts">
import AppHeader from '@/shared/ui/layout/AppHeader.vue'
import BottomNavigation from '@/shared/ui/layout/BottomNavigation.vue'
import { useLoginStore } from '@/modules/auth'

import { useRoute } from 'vue-router'
import { computed } from 'vue'
const route = useRoute()
const auth = useLoginStore()
// 详情沿用首页品牌排版；编辑/新增保留原布局，首页与地图的判断和内容不变。
const detailPage = computed(
  () => /^\/restaurants\/[^/]+$/.test(route.path) && route.path !== '/restaurants/new',
)
</script>

<template>
  <div class="app-shell">
    <AppHeader
      :discovery="route.path === '/' || detailPage"
      :authenticated="auth.authenticated"
      :username="auth.user?.username"
      :restoring="auth.restoring"
      :logging-out="auth.loggingOut"
      :has-session="auth.hasSession"
      :error="auth.error"
      @logout="auth.signOut"
      @retry="auth.restore"
    />
    <main>
      <RouterView />
    </main>
    <BottomNavigation
      v-if="!detailPage"
      :discovery="route.path === '/'"
      :authenticated="auth.authenticated"
    />
  </div>
</template>
