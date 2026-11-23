/** 按本地日历区分今天、昨天和跨年时间；不按24小时推算，避免跨午夜及夏令时误标。 */
export function notificationTime(value: string, now = new Date()): string {
  const date = new Date(value)
  const clock = `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
  const sameDay = (other: Date) => date.toDateString() === other.toDateString()
  if (sameDay(now)) return `今天 ${clock}`
  const yesterday = new Date(now)
  yesterday.setDate(yesterday.getDate() - 1)
  if (sameDay(yesterday)) return `昨天 ${clock}`
  const year = date.getFullYear() === now.getFullYear() ? '' : `${date.getFullYear()}年`
  return `${year}${date.getMonth() + 1}月${date.getDate()}日 ${clock}`
}
