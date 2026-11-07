/** 收藏传输回归：固定会话与主体，校验目标状态回执及公开列表的失效项边界。 */
import { afterEach, it, expect, vi } from 'vitest'
import { httpClient } from '@/shared/api/httpClient'
import { favoriteState, setFavorite, favoritePage, favoriteVisibility } from './favoriteApi'
const signal = () => new AbortController().signal
const item = {
  restaurantId: '1',
  name: '店铺',
  restaurantType: 'REGULAR',
  businessStatus: 'CLOSED',
  available: true,
  createdAt: '2026-11-07T00:00:00Z',
}
const payload = () => ({
  userId: '22',
  records: [item],
  total: 1,
  totalPages: 1,
  page: 1,
  pageSize: 8,
})
afterEach(() => vi.restoreAllMocks())
it('uses idempotent target-state writes with fixed credentials and validates acknowledgements', async () => {
  const put = vi
    .spyOn(httpClient, 'put')
    .mockResolvedValue({ data: { success: true, data: { restaurantId: '1', favorited: true } } })
  const del = vi
    .spyOn(httpClient, 'delete')
    .mockResolvedValue({ data: { success: true, data: { restaurantId: '1', favorited: false } } })
  await setFavorite('1', true, 'session')
  await setFavorite('1', false, 'session')
  expect(put).toHaveBeenCalledWith('/api/restaurants/1/favorite', undefined, {
    headers: { Authorization: 'Bearer session' },
    signal: undefined,
  })
  expect(del).toHaveBeenCalledWith('/api/restaurants/1/favorite', {
    headers: { Authorization: 'Bearer session' },
    signal: undefined,
  })
  put.mockResolvedValueOnce({
    data: { success: true, data: { restaurantId: '2', favorited: true } },
  })
  await expect(setFavorite('1', true, 'session')).rejects.toThrow('无法确认')
})
it('does not invent an unfavorited state after a failed read', async () => {
  vi.spyOn(httpClient, 'get').mockRejectedValue(new Error('服务暂时不可用'))
  await expect(favoriteState('1', 'session', signal())).rejects.toThrow('暂时不可用')
})
it('validates the public subject and rejects invalid placeholders in other users lists', async () => {
  const get = vi
    .spyOn(httpClient, 'get')
    .mockResolvedValue({ data: { success: true, data: payload() } })
  const s = signal()
  await favoritePage(1, '22', false, 'session', s, true)
  expect(get).toHaveBeenCalledWith('/api/users/22/favorites', {
    headers: { Authorization: 'Bearer session' },
    signal: s,
    params: { page: 1, pageSize: 8 },
  })
  get.mockResolvedValue({
    data: {
      success: true,
      data: {
        ...payload(),
        records: [
          { ...item, name: null, restaurantType: null, businessStatus: null, available: false },
        ],
      },
    },
  })
  await expect(favoritePage(1, '22', false, 'session', s, true)).rejects.toThrow('无法确认')
  const own = await favoritePage(1, '22', true, 'session', s)
  expect(own.records[0]?.available).toBe(false)
  await expect(favoritePage(1, '33', true, 'session', s)).rejects.toThrow('无法确认')
})
it('stores only explicit privacy settings for the authenticated identity', async () => {
  const patch = vi
    .spyOn(httpClient, 'patch')
    .mockResolvedValue({ data: { success: true, data: { userId: '22', favoritesPublic: true } } })
  await favoriteVisibility('22', 'session', undefined, true)
  expect(patch).toHaveBeenCalledWith(
    '/api/me/favorites/visibility',
    { favoritesPublic: true },
    { headers: { Authorization: 'Bearer session' }, signal: undefined },
  )
  await expect(favoriteVisibility('11', 'session', undefined, true)).rejects.toThrow('无法确认')
})
