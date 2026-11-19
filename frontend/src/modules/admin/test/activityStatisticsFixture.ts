import { shiftActivityDate, type ActivityStatistics } from '../model/activityStatistics'

/** 仅供测试使用，不由页面或生产入口导入。 */
export function activityStatisticsFixture(): ActivityStatistics {
  return {
    enabled: true,
    timezone: 'Asia/Shanghai',
    startedAt: '2026-10-20T16:00:00Z',
    currentDate: '2026-11-19',
    fetchedAt: '2026-11-19T06:32:00Z',
    sevenDayActiveUserCount: 436,
    sevenDayCollectionStatus: 'NORMAL',
    days: Array.from({ length: 30 }, (_, index) => {
      const date = shiftActivityDate('2026-11-19', index - 29)
      return {
        date,
        activeUserCount: index === 29 ? 128 : 72 + index * 3,
        newUserCount: index === 29 ? 12 : index % 9,
        publishedReviewCount: index === 29 ? 37 : 20 + index,
        collectionStatus: 'NORMAL',
        activeSyncedAt: date + 'T06:30:00Z',
      }
    }),
  }
}
