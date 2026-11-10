/**
 * 通用格式化工具。
 *
 * 后端评分范围是 0–10，所有面向用户的文案统一按五分制展示；
 * 缺失、越界或非有限值一律显示“—”，不能把未知当成 0 分。
 */

export function formatRating(score: number | null | undefined): string {
  if (score === null || score === undefined || !Number.isFinite(score) || score < 0 || score > 10) {
    return '—'
  }
  return (score / 2).toFixed(1)
}

/** 人均价格保留来源语义：估值必须显示“约”，未知不能显示 ¥0。 */
export function formatPrice(price: number | null, kind: 'UNKNOWN' | 'EXACT' | 'ESTIMATE'): string {
  if (price === null || !Number.isFinite(price) || price <= 0) return '人均待补'
  return kind === 'ESTIMATE' ? `约 ¥${price}` : `¥${price}`
}

/** 距离由服务端按米返回；小于 1 公里显示米，避免 0.0km 的误导。 */
export function formatDistance(distanceKm: number | null | undefined): string {
  if (distanceKm === null || distanceKm === undefined || !Number.isFinite(distanceKm)) return ''
  if (distanceKm < 1) return `${Math.round(distanceKm * 1000)}m`
  return `${distanceKm.toFixed(1)}km`
}

/** 推荐率 0–1 转百分比；缺失显示空字符串，由页面决定是否展示。 */
export function formatRecommendRate(rate: number | null | undefined): string {
  if (rate === null || rate === undefined || !Number.isFinite(rate) || rate < 0 || rate > 1) return ''
  return `${(rate * 100).toFixed(1)}%`
}

/** 接口时间为 ISO 8601 UTC；这里只做稳定的本地日期显示，不引入时区库。 */
export function formatDate(iso: string): string {
  const time = Date.parse(iso)
  if (!Number.isFinite(time)) return ''
  const date = new Date(time)
  const pad = (value: number) => `${value}`.padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** 经营状态只区分营业与停业，未知状态保持原文，不能默认成营业。 */
export function businessLabel(status: string | null | undefined): string {
  if (status === 'OPERATING') return '营业中'
  if (status === 'CLOSED') return '已停业'
  return ''
}
