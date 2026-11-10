/**
 * 小程序启动入口。
 *
 * 当前只负责标记应用已启动；登录态由 shared/auth/session 在本地存储中读写，
 * 页面在 onShow 时重新读取，避免全局缓存与真实存储不一致。
 */
App({
  globalData: {
    launchedAt: Date.now(),
  },
  onLaunch() {
    // 预留启动钩子：后续接入版本检查或主题配置时在此扩展，不在此发起业务请求。
  },
})
