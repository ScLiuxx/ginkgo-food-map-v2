import { afterEach, it, expect, vi } from 'vitest'
import { httpClient } from '@/shared/api/httpClient'
import { fetchFollowSummary, setFollow, fetchFollowPage, followPrivacy } from './followsApi'
/** 关系读取和明确目标状态写入协议，不把私密拒绝或故障伪装为零人数。 */
afterEach(() => vi.restoreAllMocks())
const value = { userId: '22', followingCount: 2, followersCount: 3, following: true }
it('uses idempotent PUT and DELETE and verifies the selected user', async () => {
  const put = vi
    .spyOn(httpClient, 'put')
    .mockResolvedValue({ data: { success: true, data: value } })
  const del = vi
    .spyOn(httpClient, 'delete')
    .mockResolvedValue({ data: { success: true, data: { ...value, following: false } } })
  await setFollow('22', true, 'session')
  await setFollow('22', false, 'session')
  expect(put).toHaveBeenCalledWith('/api/users/22/follow', undefined, {
    headers: { Authorization: 'Bearer session' },
    signal: undefined,
  })
  expect(del).toHaveBeenCalledTimes(1)
  await expect(setFollow('33', true, 'session')).rejects.toThrow('无法确认')
})
it('does not manufacture summary or private list results on failure', async () => {
  vi.spyOn(httpClient, 'get').mockRejectedValue(new Error('名单仅本人可见'))
  await expect(
    fetchFollowPage('22', 'FOLLOWERS', 1, 'session', new AbortController().signal),
  ).rejects.toThrow('仅本人可见')
  await expect(fetchFollowSummary('22', 'session', new AbortController().signal)).rejects.toThrow()
})
it('accepts bounded member pages and rejects cross-user and duplicate responses', async () => {
  const data = {
    userId: '22',
    kind: 'FOLLOWING',
    records: [{ userId: '33', username: '食友', level: 2, following: false }],
    total: 1,
    totalPages: 1,
    page: 1,
    pageSize: 8,
  }
  const get = vi.spyOn(httpClient, 'get').mockResolvedValue({ data: { success: true, data } })
  expect(
    (await fetchFollowPage('22', 'FOLLOWING', 1, 'session', new AbortController().signal))
      .records[0]?.level,
  ).toBe(2)
  get.mockResolvedValue({
    data: {
      success: true,
      data: { ...data, records: [...data.records, ...data.records], total: 2 },
    },
  })
  await expect(
    fetchFollowPage('22', 'FOLLOWING', 1, 'session', new AbortController().signal),
  ).rejects.toThrow('无法确认')
})
it('sends independent privacy values using the fixed session', async () => {
  const patch = vi.spyOn(httpClient, 'patch').mockResolvedValue({
    data: {
      success: true,
      data: { userId: '11', followingPublic: true, followersPublic: false },
    },
  })
  await followPrivacy('11', 'session', undefined, { followingPublic: true, followersPublic: false })
  expect(patch).toHaveBeenCalledWith(
    '/api/me/follows/privacy',
    { followingPublic: true, followersPublic: false },
    { headers: { Authorization: 'Bearer session' }, signal: undefined },
  )
})
