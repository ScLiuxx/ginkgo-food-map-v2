<script setup lang="ts">
import { hasUnreadNotifications } from '@/modules/message/model/useRealtimeUnread'
import AppHeader from '@/shared/ui/layout/AppHeader.vue'
import AdminLayout from './AdminLayout.vue'
import BottomNavigation from '@/shared/ui/layout/BottomNavigation.vue'
import { useLoginStore } from '@/modules/auth'
import { ReviewComposer, notifyReviewPublished } from '@/modules/review'
import { hasDetailComposer } from '@/shared/lib/reviewNavigation'

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
const mapPage = computed(() => route.path === '/map' || route.path === '/previews/map')
</script>

<template>
  <!-- 管理端沿用示意图的独立页头与侧栏，不叠加前台页头及底部导航。 -->
  <AdminLayout v-if="route.meta.layout === 'admin'" />
  <section
    v-else-if="!routeReady || resolvingIdentity || auth.isAdministrator"
    class="identity-check"
    role="status"
  >
    <!-- 首次路由尚未就绪时不展示导航，避免把当前来路误读成默认首页。 -->
    <h1>
      {{ !routeReady ? '正在加载页面' : auth.error ? '暂时无法验证登录状态' : '正在验证登录状态' }}
    </h1>
    <p>{{ auth.error || '验证完成后进入对应页面。' }}</p>
    <template v-if="auth.error"
      ><button :disabled="auth.restoring" @click="auth.restore">重新验证</button
      ><button :disabled="auth.loggingOut" @click="auth.signOut">退出当前会话</button></template
    >
  </section>
  <div
    v-else
    class="app-shell"
    :class="{ 'ranking-shell': route.meta.ranking, 'map-shell': mapPage }"
  >
    <AppHeader
      :discovery="route.path === '/' || detailPage || !!route.meta.ranking"
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
    <!-- 全站写评留在来源页；店铺和窗口详情继续使用自己的固定对象编辑器。 -->
    <ReviewComposer
      v-if="!hasDetailComposer(route.path)"
      hide-trigger
      @published="notifyReviewPublished"
    />
    <BottomNavigation
      v-if="!detailPage"
      :has-unread-notifications="hasUnreadNotifications"
      :discovery="route.path === '/' || !!route.meta.ranking"
      :authenticated="auth.authenticated"
    />
  </div>
</template>

<style scoped>
/* 手机地图采用单视口布局，底栏参与布局，避免地图和拖动面板落到固定底栏后面。 */
@media (max-width: 700px) {
  /* 移动浏览器的100vh可能大于可视高度，解除全站body最小高度以免地址栏出现时整页仍可滚动。 */
  :global(body:has(.map-shell)) {
    min-height: 0;
  }
  .map-shell {
    height: 100dvh;
    min-height: 0;
    padding-bottom: 0;
    overflow: hidden;
    display: flex;
    flex-direction: column;
  }
  .map-shell :deep(.topbar) {
    display: none;
  }
  .map-shell > main {
    flex: 1;
    min-height: 0;
    display: flex;
  }
  .map-shell :deep(.bottom-navigation) {
    position: static;
    flex-shrink: 0;
  }
}
/* 榜单品牌及底栏按已确认图稿局部调整，不改变其他页面的共享布局。 */
.ranking-shell {
  padding-bottom: 0;
}
.ranking-shell :deep(.topbar.is-discovery) {
  max-width: 1056px;
}
.ranking-shell :deep(.is-discovery .brand strong) {
  font-size: 23px;
  gap: 12px;
}
.ranking-shell :deep(.is-discovery .brand em) {
  font-size: 32px;
  color: #ee970f;
  font-weight: 850;
}
.ranking-shell :deep(.navigation-items) {
  max-width: 900px;
}
.ranking-shell :deep(.navigation-items svg) {
  width: 22px;
  height: 22px;
}
.ranking-shell :deep(.compose-icon) {
  background: #f7f0e4;
  color: #a96c1e;
}
@media (max-width: 600px) {
  .ranking-shell :deep(.topbar.is-discovery) {
    width: calc(100% - 36px);
    height: 65px;
  }
  .ranking-shell :deep(.is-discovery .brand strong) {
    font-size: 17px;
    gap: 8px;
  }
  .ranking-shell :deep(.is-discovery .brand em) {
    font-size: 25px;
  }
  .ranking-shell :deep(.navigation-items) {
    height: 60px;
  }
  .ranking-shell :deep(.navigation-items svg) {
    width: 21px;
    height: 21px;
  }
}
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
