/**
 * 小程序运行环境与后端地址。
 *
 * 微信小程序要求请求域名在公众平台白名单中，并且正式版必须使用 HTTPS。
 * 开发版指向本机后端，体验版与正式版使用项目的 HTTPS 后端。
 */
const DEVELOPMENT_API_BASE_URL = 'http://localhost:8080'
const PRODUCTION_API_BASE_URL = 'https://nidianfoodmap.cn'

/** 读取失败时按开发环境处理，避免本地开发请求误发到线上。 */
function resolveApiBaseUrl(): string {
  try {
    const envVersion = wx.getAccountInfoSync().miniProgram.envVersion
    return envVersion === 'trial' || envVersion === 'release'
      ? PRODUCTION_API_BASE_URL
      : DEVELOPMENT_API_BASE_URL
  } catch {
    return DEVELOPMENT_API_BASE_URL
  }
}

export const API_BASE_URL = resolveApiBaseUrl()

/** 后端返回的图片和接口路径都是站内相对地址，展示前统一拼接当前环境域名。 */
export function absoluteUrl(path: string): string {
  if (/^https?:\/\//i.test(path)) return path
  const suffix = path.startsWith('/') ? path : `/${path}`
  return `${API_BASE_URL}${suffix}`
}
