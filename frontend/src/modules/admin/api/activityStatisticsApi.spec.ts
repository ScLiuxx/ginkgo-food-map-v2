import { beforeEach, describe, expect, it, vi } from 'vitest'
import { httpClient } from '@/shared/api/httpClient'
import { fetchActivityStatistics } from './activityStatisticsApi'
import { activityStatisticsFixture } from '../test/activityStatisticsFixture'

vi.mock('@/shared/api/httpClient', () => ({ httpClient: { get: vi.fn() } }))
const load = () => fetchActivityStatistics('original-token', new AbortController().signal)
const respond = (data: unknown) =>
  vi.mocked(httpClient.get).mockResolvedValue({ data: { success: true, data } })

describe('统计读取协议投影', () => {
  beforeEach(() => vi.resetAllMocks())
  it('固定发起凭证和取消信号，仅投影统计白名单，不重算七日人数', async () => {
    const data = activityStatisticsFixture()
    Object.assign(data, { users: ['private-account'] })
    Object.assign(data.days[0]!, { userId: 'private-id' })
    respond(data)
    const signal = new AbortController().signal
    const result = await fetchActivityStatistics('original-token', signal)
    expect(httpClient.get).toHaveBeenCalledWith('/api/admin/activity-statistics', {
      signal,
      headers: { Authorization: 'Bearer original-token' },
    })
    expect(result.sevenDayActiveUserCount).toBe(436)
    expect(result).not.toHaveProperty('users')
    expect(result.days[0]).not.toHaveProperty('userId')
  })
  it('未启用允许空结果，不能带假0或历史数据', async () => {
    const data = {
      ...activityStatisticsFixture(),
      enabled: false,
      startedAt: null,
      days: [],
      sevenDayActiveUserCount: null,
      sevenDayCollectionStatus: 'UNKNOWN',
    }
    respond(data)
    expect((await load()).enabled).toBe(false)
    respond({ ...data, sevenDayActiveUserCount: 0 })
    await expect(load()).rejects.toThrow('统计数据无法确认')
  })
  it.each([
    'duplicate',
    'before-start',
    'future',
    'invalid-date',
    'unsafe-count',
    'string-count',
    'missing-sync',
    'wrong-zone',
    'normal-null',
  ])('拒绝不一致结果：%s', async (problem) => {
    const data = activityStatisticsFixture()
    const last = data.days[29]!
    if (problem === 'duplicate') data.days[0] = { ...last }
    if (problem === 'before-start') data.startedAt = '2026-11-19T06:00:00Z'
    if (problem === 'future') last.date = '2026-11-20'
    if (problem === 'invalid-date') last.date = '2026-02-30'
    if (problem === 'unsafe-count') last.newUserCount = Number.MAX_SAFE_INTEGER + 1
    if (problem === 'string-count') Object.assign(last, { newUserCount: '12' })
    if (problem === 'missing-sync') last.activeSyncedAt = null
    if (problem === 'wrong-zone') Object.assign(data, { timezone: 'UTC' })
    if (problem === 'normal-null') last.newUserCount = null
    respond(data)
    await expect(load()).rejects.toThrow('统计数据无法确认')
  })
})
