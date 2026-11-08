<script setup lang="ts">
import { hasUnreadNotifications } from '@/modules/message/model/useRealtimeUnread'
import AppHeader from '@/shared/ui/layout/AppHeader.vue'
import AdminLayout from './AdminLayout.vue'
import BottomNavigation from '@/shared/ui/layout/BottomNavigation.vue'
import { useLoginStore } from '@/modules/auth'

import { useRoute, useRouter } from 'vue-router'
import { computed, ref, watchEffect } from 'vue'
const route = useRoute()
const router = useRouter()
const auth = useLoginStore()
// 初次懒加载路由完成前meta仍为空，不能把管理端直达链接误判为前台并跳去审核。
const routeReady = ref(false)
void router.isReady().then(() => {
  routeReady.value = true
})
const resolvingIdentity = computed(() => auth.hasSession && (auth.restoring || !auth.authenticated))
// 管理账号仅显示工作台；刷新或跨标签页切换身份时先验证，防止短暂挂载普通页面。
watchEffect(() => {
  if (routeReady.value && auth.isAdministrator && !auth.restoring && route.meta.layout !== 'admin')
    void router.replace('/admin/reviews')
})
// 详情沿用首页品牌排版；编辑/新增保留原布局，首页与地图的判断和内容不变。
const detailPage = computed(
  () => /^\/restaurants\/[^/]+$/.test(route.path) && route.path !== '/restaurants/new',
)
</script>

<template>
  <!-- 管理端沿用示意图的独立页头与侧栏，不叠加前台页头及底部导航。 -->
  <AdminLayout v-if="route.meta.layout === 'admin'" />
  <section
    v-else-if="resolvingIdentity || auth.isAdministrator"
    class="identity-check"
    role="status"
  >
    <h1>{{ auth.error ? '暂时无法验证登录状态' : '正在验证登录状态' }}</h1>
    <p>{{ auth.error || '验证完成后进入对应页面。' }}</p>
    <template v-if="auth.error"
      ><button :disabled="auth.restoring" @click="auth.restore">重新验证</button
      ><button :disabled="auth.loggingOut" @click="auth.signOut">退出当前会话</button></template
    >
  </section>
  <div v-else class="app-shell">
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
      :has-unread-notifications="hasUnreadNotifications"
      :discovery="route.path === '/'"
      :authenticated="auth.authenticated"
    />
  </div>
</template>

<style scoped>
.identity-check {
  max-width: 560px;
  margin: 100px auto;
  padding: 24px;
  text-align: center;
  color: #55616d;
}
.identity-check h1 {
  font-size: 23px;
  font-weight: 600;
}
.identity-check p {
  font-size: 13px;
  line-height: 1.8;
  color: #89929c;
}
.identity-check button {
  border: 1px solid #e9dfcf;
  border-radius: 5px;
  background: #fff8ed;
  color: #a96c27;
  padding: 10px 16px;
  margin: 10px;
  cursor: pointer;
}
</style>
