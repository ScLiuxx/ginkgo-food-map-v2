import { createRouter, createWebHistory } from 'vue-router'
import { nextTick } from 'vue'
import { waitForPageLayout } from '@/shared/lib/restorePageScroll'
import { useWindowManagementBrowsing } from '@/modules/admin/model/windowManagementBrowsing'

let scrollGeneration = 0

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    // 示例数据仅在开发环境单独预览，生产构建不提供该路由或导入示例资源。
    ...(import.meta.env.DEV
      ? [
          {
            path: '/previews/restaurant-rankings',
            component: () => import('@/pages/restaurant/RestaurantRankingPreviewPage.vue'),
            meta: { title: '榜单界面预览', ranking: true },
          },
        ]
      : []),
    // 管理端共用独立布局，身份验证通过后才挂载页面；审核使用真实申请及服务端处理结果。
    {
      path: '/admin/login',
      name: 'admin-login',
      redirect: '/login',
    },
    {
      path: '/admin',
      redirect: '/admin/reviews',
    },
    {
      path: '/admin/reviews',
      component: () => import('@/pages/admin/AdminReviewPage.vue'),
      meta: { title: '商户审核 · 管理工作台', layout: 'admin' },
    },
    {
      path: '/admin/activity',
      component: () => import('@/pages/admin/AdminActivityPage.vue'),
      meta: { title: '使用统计 · 管理工作台', layout: 'admin' },
    },
    {
      path: '/admin/announcements',
      component: () => import('@/pages/admin/AdminAnnouncementPage.vue'),
      meta: { title: '公告管理 · 管理工作台', layout: 'admin' },
    },
    {
      path: '/admin/reports',
      component: () => import('@/pages/admin/AdminReportPage.vue'),
      meta: { title: '举报处理 · 管理工作台', layout: 'admin' },
    },
    {
      path: '/admin/restaurants',
      component: () => import('@/pages/admin/AdminRestaurantPage.vue'),
      meta: { title: '商户管理 · 管理工作台', layout: 'admin' },
    },
    {
      path: '/admin/restaurants/:id/windows',
      component: () => import('@/pages/admin/AdminWindowPage.vue'),
      props: true,
      meta: { title: '窗口管理 · 管理工作台', layout: 'admin' },
    },
    {
      path: '/admin/restaurants/:id/windows/new',
      component: () => import('@/pages/admin/AdminWindowPage.vue'),
      props: (route) => ({ id: route.params.id, editing: true }),
      meta: { title: '新增窗口 · 管理工作台', layout: 'admin' },
    },
    {
      path: '/admin/restaurants/:id/windows/:windowId/edit',
      component: () => import('@/pages/admin/AdminWindowPage.vue'),
      props: (route) => ({ id: route.params.id, windowId: route.params.windowId, editing: true }),
      meta: { title: '编辑窗口 · 管理工作台', layout: 'admin' },
    },
    {
      path: '/admin/restaurants/new',
      component: () => import('@/pages/admin/AdminRestaurantPage.vue'),
      props: { editing: true },
      meta: { title: '新增店铺 · 管理工作台', layout: 'admin' },
    },
    {
      path: '/admin/restaurants/:id/edit',
      component: () => import('@/pages/admin/AdminRestaurantPage.vue'),
      props: (route) => ({ editing: true, id: route.params.id }),
      meta: { title: '编辑店铺 · 管理工作台', layout: 'admin' },
    },
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
    // 消息分类使用独立地址，直达、刷新及浏览器返回均能恢复对应页面。
    {
      path: '/messages/:category(likes|comments|followers|system)?',
      component: () => import('@/pages/message/MessagePage.vue'),
      props: true,
      meta: { title: '消息' },
    },
    // 兼容先前界面地址；审核归入系统消息，已暂缓的私信地址返回消息中心。
    { path: '/messages/reviews', redirect: '/messages/system' },
    { path: '/messages/private', redirect: '/messages' },
    {
      path: '/account/:section(reviews|favorites|contributions|settings|growth|following|followers)?',
      component: () => import('@/pages/account/AccountPage.vue'),
      props: true,
      meta: { title: '我的' },
    },
    {
      path: '/users/:publicUserId([1-9]\\d*)/:section(following|followers)',
      component: () => import('@/pages/account/PublicProfilePage.vue'),
      props: true,
      meta: { title: '关注与粉丝' },
    },
    // 公开主页要求登录；页面不以路由参数或本地缓存冒充已授权的用户资料。
    {
      path: '/users/:publicUserId([1-9]\\d*)',
      component: () => import('@/pages/account/PublicProfilePage.vue'),
      props: true,
      meta: { title: '个人主页' },
    },
    {
      path: '/restaurant-rankings',
      name: 'restaurant-rankings',
      component: () => import('@/pages/restaurant/RestaurantRankingPage.vue'),
      meta: { title: '近期推荐榜', ranking: true },
    },
    {
      path: '/account/contributions/:requestId/edit',
      component: () => import('@/modules/restaurant/ui/RestaurantCorrection.vue'),
      props: (route) => ({ requestId: route.params.requestId, submission: true }),
      meta: { title: '修改申请' },
    },
    {
      path: '/restaurants/submit',
      name: 'restaurant-submit',
      component: () => import('@/modules/restaurant/ui/RestaurantCorrection.vue'),
      props: { submission: true },
      meta: { title: '提交店铺' },
    },
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
      path: '/restaurants/:id/correction',
      name: 'restaurant-correction',
      component: () => import('@/modules/restaurant/ui/RestaurantCorrection.vue'),
      props: true,
      meta: { title: '补充 / 纠错' },
    },
    {
      path: '/canteen-windows/:id',
      component: () => import('@/pages/restaurant/RestaurantPage.vue'),
      props: (route) => ({ id: route.params.id, window: true }),
      meta: { title: '窗口详情' },
    },
    {
      path: '/restaurants/:id',
      name: 'restaurant-detail',
      component: () => import('@/pages/restaurant/RestaurantPage.vue'),
      props: true,
      meta: { title: '餐厅详情' },
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
  scrollBehavior: async (to, from, savedPosition) => {
    const ticket = ++scrollGeneration
    // 窗口列表会异步重读；普通“返回”与历史返回都等待表格就绪，避免路由默认归零覆盖现场。
    if (/^\/admin\/restaurants\/[1-9]\d*\/windows$/.test(to.path)) {
      await nextTick()
      if (!(await waitForPageLayout(() => ticket === scrollGeneration, '.window-management')))
        return false
      const context = useWindowManagementBrowsing().context
      return ticket === scrollGeneration
        ? context.canteenId === to.params.id
          ? { left: 0, top: context.scrollTop }
          : (savedPosition ?? { top: 0 })
        : false
    }
    // 替换分页或筛选参数不重置阅读位置；真正的历史回退等待列表读取后再恢复。
    if (!savedPosition) return to.path === from.path && to.hash === from.hash ? false : { top: 0 }
    // 首页返回同样等待目录挂载/首批读取；手机缓存已有全部卡片时可直接恢复原位置。
    const selector =
      to.path === '/'
        ? '.restaurant-directory'
        : /^\/(?:account|users)(?:\/|$)/.test(to.path)
          ? '.account-center'
          : /^\/(?:restaurants|canteen-windows)\/[1-9]\d*$/.test(to.path)
            ? '.detail-page'
            : undefined
    if (selector) {
      await nextTick()
      if (!(await waitForPageLayout(() => ticket === scrollGeneration, selector))) return false
    }
    return ticket === scrollGeneration ? savedPosition : false
  },
})

// 单页应用切换路由时同步浏览器标题，便于用户识别当前页面。
router.afterEach((to) => {
  document.title = to.meta.title ? `${String(to.meta.title)} · 泥电美食地图` : '泥电美食地图'
})

export default router
