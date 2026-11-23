import { absoluteUrl } from '../../config/env'

/**
 * 公开头像地址。
 *
 * 头像接口允许游客读取，因此 <image> 不能附加 Authorization；无头像时服务端返回 204，
 * 由页面 binderror 回退到用户名首字。version 只用于本人替换头像后绕过图片缓存。
 */
export function userAvatarUrl(userId: string, version?: number): string {
  if (!/^[1-9]\d*$/.test(userId)) return ''
  const resolved = version ?? readAvatarVersion(userId)
  const suffix = resolved ? `?v=${resolved}` : ''
  return absoluteUrl(`/api/users/${encodeURIComponent(userId)}/avatar${suffix}`)
}

const VERSION_PREFIX = 'avatar-version:'

function readAvatarVersion(userId: string): number | undefined {
  try {
    const value = Number(wx.getStorageSync(`${VERSION_PREFIX}${userId}`))
    return Number.isFinite(value) && value > 0 ? value : undefined
  } catch {
    return undefined
  }
}

/**
 * 本人替换头像后记录本地版本号。
 *
 * 头像接口可能被图片缓存命中；只在本机记录时间戳即可让所有页面重新取图，
 * 不改变服务端缓存策略，也不把版本号发送给其他用户。
 */
export function markAvatarUpdated(userId: string): void {
  if (!/^[1-9]\d*$/.test(userId)) return
  try {
    wx.setStorageSync(`${VERSION_PREFIX}${userId}`, Date.now())
  } catch {
    // 本地存储失败只影响缓存刷新，不阻断头像更新结果。
  }
}
