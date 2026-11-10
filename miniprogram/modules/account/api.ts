import { request } from '../../shared/api/request'
import { getCurrentUser } from '../../shared/auth/session'

export interface GrowthView {
  level: number
  levelLabel: string
  experience: number
  currentThreshold: number
  nextThreshold: number | null
  progressPercent: number
  progressText: string
  nextText: string
}

export interface ProfileView {
  userId: string
  username: string
  maskedEmail: string
  bio: string
}

const invalidGrowth = new Error('成长数据无法确认，请重新加载。')
const invalidProfile = new Error('资料无法确认，请重新加载。')

function isNonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

/**
 * 回执必须属于当前本地会话。
 *
 * 换号后旧请求即使成功返回，也不能把上一账号的经验或资料写入新页面。
 */
function ensureCurrentUser(userId: string): void {
  const user = getCurrentUser()
  if (!user || String(user.id) !== userId) {
    throw new Error('账号已切换，请重新加载。')
  }
}

/**
 * 读取本人成长摘要。
 *
 * 成长接口只按认证身份查询，不接受 userId 参数；等级与门槛全部以服务端为准，
 * 前端不根据经验自行推算等级。
 */
export async function fetchGrowth(): Promise<GrowthView> {
  const raw = await request<{
    userId: string
    experience: number
    level: number
    currentLevelThreshold: number
    nextLevelThreshold: number | null
  }>({ path: '/api/me/growth', auth: 'required' })

  if (
    !raw ||
    typeof raw.userId !== 'string' ||
    !/^[1-9]\d*$/.test(raw.userId) ||
    !isNonNegativeInteger(raw.experience) ||
    !Number.isSafeInteger(raw.level) ||
    raw.level < 1 ||
    !isNonNegativeInteger(raw.currentLevelThreshold) ||
    (raw.nextLevelThreshold !== null &&
      (!isNonNegativeInteger(raw.nextLevelThreshold) ||
        raw.nextLevelThreshold <= raw.currentLevelThreshold))
  ) {
    throw invalidGrowth
  }

  ensureCurrentUser(raw.userId)

  const next = raw.nextLevelThreshold
  const segment = next === null ? 0 : next - raw.currentLevelThreshold
  const gained = Math.max(0, raw.experience - raw.currentLevelThreshold)
  const percent = next === null ? 100 : Math.min(100, Math.round((gained / segment) * 100))
  return {
    level: raw.level,
    levelLabel: `Lv.${raw.level}`,
    experience: raw.experience,
    currentThreshold: raw.currentLevelThreshold,
    nextThreshold: next,
    progressPercent: percent,
    progressText:
      next === null ? `${raw.experience} 经验` : `${gained} / ${segment} 经验`,
    nextText:
      next === null
        ? '已达到当前最高等级'
        : `距离 Lv.${raw.level + 1} 还需 ${Math.max(0, next - raw.experience)} 经验`,
  }
}

function toProfile(raw: {
  userId: string
  username: string
  maskedEmail: string
  bio: string
}): ProfileView {
  if (
    !raw ||
    typeof raw.userId !== 'string' ||
    !/^[1-9]\d*$/.test(raw.userId) ||
    typeof raw.username !== 'string' ||
    !raw.username.trim() ||
    typeof raw.maskedEmail !== 'string' ||
    !raw.maskedEmail.trim() ||
    typeof raw.bio !== 'string'
  ) {
    throw invalidProfile
  }
  ensureCurrentUser(raw.userId)
  return {
    userId: raw.userId,
    username: raw.username,
    maskedEmail: raw.maskedEmail,
    bio: raw.bio,
  }
}

/** 读取本人资料；服务端只返回用户名、脱敏邮箱和简介，不返回完整邮箱。 */
export async function fetchProfile(): Promise<ProfileView> {
  const raw = await request<ProfileView>({ path: '/api/me/profile', auth: 'required' })
  return toProfile(raw)
}

/**
 * 保存本人简介。
 *
 * bio 是必填字符串，空字符串表示清空；用户名、邮箱和角色不能通过此接口修改。
 * 规范化、长度与联系方式校验以服务端为准，前端只做即时计数和基础超长提示。
 */
export async function saveProfile(bio: string): Promise<ProfileView> {
  const raw = await request<ProfileView>({
    path: '/api/me/profile',
    method: 'PATCH',
    auth: 'required',
    data: { bio },
  })
  return toProfile(raw)
}
