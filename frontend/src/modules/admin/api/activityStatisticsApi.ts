import { httpClient } from '@/shared/api/httpClient'
import type { ApiResponse } from '@/shared/api/api.types'
import {
  beijingDate,
  shiftActivityDate,
  type ActivityStatistics,
  type CollectionStatus,
} from '../model/activityStatistics'

const invalid = () => new Error('统计数据无法确认，请重新读取。')
const status = (value: unknown): value is CollectionStatus =>
  value === 'NORMAL' || value === 'PARTIAL' || value === 'UNKNOWN'
const count = (value: unknown): value is number | null =>
  value === null || (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0)
const instant = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^\d{4}-\d{2}-\d{2}T.*(?:Z|[+-]\d{2}:\d{2})$/.test(value) &&
  Number.isFinite(Date.parse(value))
const day = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^\d{4}-\d{2}-\d{2}$/.test(value) &&
  Number.isFinite(Date.parse(value)) &&
  new Date(value).toISOString().slice(0, 10) === value

/** 固定凭证读取；投影白名单字段，不把缺失、错误或未采集日期变成0。 */
export async function fetchActivityStatistics(
  token: string,
  signal: AbortSignal,
): Promise<ActivityStatistics> {
  const response = await httpClient.get<ApiResponse<ActivityStatistics>>(
    '/api/admin/activity-statistics',
    {
      signal,
      headers: { Authorization: `Bearer ${token}` },
    },
  )
  const data = response.data?.data
  if (
    response.data?.success !== true ||
    !data ||
    typeof data.enabled !== 'boolean' ||
    data.timezone !== 'Asia/Shanghai' ||
    !day(data.currentDate) ||
    !instant(data.fetchedAt) ||
    beijingDate(data.fetchedAt) !== data.currentDate ||
    !count(data.sevenDayActiveUserCount) ||
    !status(data.sevenDayCollectionStatus) ||
    !Array.isArray(data.days) ||
    data.days.length > 30
  )
    throw invalid()
  if (
    data.enabled
      ? !instant(data.startedAt) || Date.parse(data.startedAt) > Date.parse(data.fetchedAt)
      : data.startedAt !== null ||
        data.days.length !== 0 ||
        data.sevenDayActiveUserCount !== null ||
        data.sevenDayCollectionStatus !== 'UNKNOWN'
  )
    throw invalid()
  if (data.sevenDayCollectionStatus === 'NORMAL' && data.sevenDayActiveUserCount === null)
    throw invalid()
  const seen = new Set<string>()
  const firstDate = shiftActivityDate(data.currentDate, -29)
  const started = data.startedAt ? beijingDate(data.startedAt) : null
  const days = data.days
    .map((row) => {
      if (
        !row ||
        !day(row.date) ||
        seen.has(row.date) ||
        row.date < firstDate ||
        row.date > data.currentDate ||
        !started ||
        row.date < started ||
        !status(row.collectionStatus) ||
        !count(row.activeUserCount) ||
        !count(row.newUserCount) ||
        !count(row.publishedReviewCount)
      )
        throw invalid()
      if (
        row.activeUserCount === null
          ? row.activeSyncedAt !== null
          : !instant(row.activeSyncedAt) ||
            Date.parse(row.activeSyncedAt) > Date.parse(data.fetchedAt)
      )
        throw invalid()
      if (
        row.collectionStatus === 'NORMAL' &&
        [row.activeUserCount, row.newUserCount, row.publishedReviewCount].includes(null)
      )
        throw invalid()
      seen.add(row.date)
      return {
        date: row.date,
        activeUserCount: row.activeUserCount,
        newUserCount: row.newUserCount,
        publishedReviewCount: row.publishedReviewCount,
        collectionStatus: row.collectionStatus,
        activeSyncedAt: row.activeSyncedAt,
      }
    })
    .sort((a, b) => a.date.localeCompare(b.date))
  return {
    enabled: data.enabled,
    timezone: 'Asia/Shanghai',
    startedAt: data.startedAt,
    currentDate: data.currentDate,
    fetchedAt: data.fetchedAt,
    sevenDayActiveUserCount: data.sevenDayActiveUserCount,
    sevenDayCollectionStatus: data.sevenDayCollectionStatus,
    days,
  }
}
