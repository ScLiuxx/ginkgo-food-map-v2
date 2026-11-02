import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/previews/review-history',
      component: () => import('@/pages/review/ReviewHistoryPreviewPage.vue'),
      meta: { title: '多次点评展示预览' },
    },
    {
      path: '/map',
      component: () => import('@/pages/map/MapPage.vue'),
      meta: { title: '地图找店' },
    },
    {
      path: '/previews/map',
      component: () => import('@/pages/map/MapPage.vue'),
      props: { preview: true },
      meta: { title: '地图界面预览' },
    },
    ...(['messages', 'account'] as const).map((kind) => ({
      path: `/${kind}`,
      component: () => import('@/pages/home/CapabilityPage.vue'),
      props: { kind },
      meta: { title: { map: '地图', messages: '消息', account: '我的' }[kind] },
    })),
    {
      path: '/restaurants/new',
      name: 'restaurant-create',
      component: () => import('@/pages/restaurant/RestaurantEditorPage.vue'),
      meta: { title: '新增餐厅' },
    },
    {
      path: '/restaurants/:id/edit',
      name: 'restaurant-edit',
      component: () => import('@/pages/restaurant/RestaurantEditorPage.vue'),
      props: true,
      meta: { title: '编辑餐厅' },
    },
    {
      path: '/restaurants/:id',
      name: 'restaurant-detail',
      component: () => import('@/pages/restaurant/RestaurantPage.vue'),
      props: true,
      meta: { title: '餐厅详情' },
    },
    {
      path: '/scoring-guide',
      name: 'scoring-guide',
      component: () => import('@/pages/scoring/ScoringGuidePage.vue'),
      meta: { title: '评分说明' },
    },
    {
      path: '/password-reset',
      name: 'password-reset',
      component: () => import('@/pages/password-reset/PasswordResetPage.vue'),
      meta: { title: '找回密码' },
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/pages/login/LoginPage.vue'),
      meta: { title: '登录账号' },
    },
    {
      path: '/',
      name: 'home',
      component: () => import('@/pages/home/HomePage.vue'),
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/pages/register/RegisterPage.vue'),
      meta: { title: '注册账号' },
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/',
    },
  ],
  // 浏览器前进/后退恢复列表阅读位置，普通跳转仍从新页面顶部开始。
  scrollBehavior: (_to, _from, savedPosition) => savedPosition ?? { top: 0 },
})

// 单页应用切换路由时同步浏览器标题，便于用户识别当前页面。
router.afterEach((to) => {
  document.title = to.meta.title ? `${String(to.meta.title)} · 泥电美食地图` : '泥电美食地图'
})

export default router
