/**
 * 微信小程序运行时最小类型声明。
 *
 * 说明：
 * - 仓库不依赖 npm 安装官方类型包，避免小程序端为了类型检查引入额外依赖；
 * - 这里只声明当前代码实际使用的 API，字段名与微信官方文档保持一致；
 * - 新增页面或调用新的 wx API 时，必须在此补充真实签名，禁止用 any 绕过校验。
 */

interface WxRequestSuccess {
  statusCode: number
  data: unknown
  header: Record<string, string>
}

interface WxRequestOptions {
  url: string
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' | 'HEAD' | 'OPTIONS'
  data?: unknown
  header?: Record<string, string>
  timeout?: number
  dataType?: 'json' | '其他'
  success?: (result: WxRequestSuccess) => void
  fail?: (error: { errMsg: string }) => void
  complete?: () => void
}

interface WxRequestTask {
  abort(): void
}

interface WxToastOptions {
  title: string
  icon?: 'success' | 'error' | 'loading' | 'none'
  duration?: number
  mask?: boolean
}

interface WxModalOptions {
  title?: string
  content?: string
  showCancel?: boolean
  confirmText?: string
  cancelText?: string
  success?: (result: { confirm: boolean; cancel: boolean }) => void
}

interface WxNavigationOptions {
  url: string
  success?: () => void
  fail?: () => void
}

interface WxPreviewImageOptions {
  urls: string[]
  current?: string
}

interface WxSystemInfo {
  statusBarHeight: number
  windowWidth: number
  windowHeight: number
  safeArea?: { top: number; bottom: number; left: number; right: number }
}

interface WxAccountInfo {
  miniProgram: {
    appId: string
    envVersion: 'develop' | 'trial' | 'release' | string
  }
}

interface Wx {
  request(options: WxRequestOptions): WxRequestTask
  getStorageSync(key: string): unknown
  setStorageSync(key: string, value: unknown): void
  removeStorageSync(key: string): void
  showToast(options: WxToastOptions): void
  showModal(options: WxModalOptions): void
  navigateTo(options: WxNavigationOptions): void
  redirectTo(options: WxNavigationOptions): void
  switchTab(options: WxNavigationOptions): void
  reLaunch(options: WxNavigationOptions): void
  navigateBack(options?: { delta?: number }): void
  startPullDownRefresh(): void
  stopPullDownRefresh(): void
  previewImage(options: WxPreviewImageOptions): void
  getSystemInfoSync(): WxSystemInfo
  getAccountInfoSync(): WxAccountInfo
  setNavigationBarTitle(options: { title: string }): void
}

declare const wx: Wx

/** 小程序事件对象只声明页面实际读取的字段。 */
interface WxTapEvent {
  currentTarget: { dataset: Record<string, string | number | boolean | undefined> }
}

interface WxInputEvent {
  detail: { value: string }
}

interface WxPickerEvent {
  detail: { value: string }
}

/** 页面实例能力；ThisType 让 Page 方法中的 this.data / this.setData 获得类型提示。 */
interface PageInstance<D extends object> {
  data: D
  route: string
  setData(data: Partial<D> | Record<string, unknown>, callback?: () => void): void
}

interface PageOptions<D extends object, C extends object> {
  data?: D
  /** 允许页面定义 WXML 事件处理函数；核心方法仍应在 Custom 中显式声明以便 this 调用。 */
  [key: string]: unknown
  onLoad?(query: Record<string, string | undefined>): void
  onReady?(): void
  onShow?(): void
  onHide?(): void
  onUnload?(): void
  onPullDownRefresh?(): void
  onReachBottom?(): void
  onShareAppMessage?(): { title?: string; path?: string }
}

declare function Page<D extends object, C extends object = {}>(
  options: PageOptions<D, C> & ThisType<PageInstance<D> & C> & C,
): void

interface AppOptions {
  globalData?: Record<string, unknown>
  onLaunch?(): void
  onShow?(): void
  onHide?(): void
}

declare function App(options: AppOptions): void
declare function getApp<T = { globalData: Record<string, unknown> }>(): T
declare function getCurrentPages(): Array<{ route: string; options: Record<string, string> }>

/** 小程序基础库提供定时器，但 ES 标准库不含浏览器声明，这里显式补充。 */
declare function setTimeout(handler: () => void, timeout?: number): number
declare function clearTimeout(id: number): void
