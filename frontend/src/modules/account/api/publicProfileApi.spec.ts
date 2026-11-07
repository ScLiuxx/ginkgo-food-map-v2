import { afterEach, it, expect, vi } from 'vitest'
import { httpClient } from '@/shared/api/httpClient'
import { fetchPublicProfile } from './publicProfileApi'
afterEach(() => vi.restoreAllMocks())
it('fixes the target and session, strips extra fields and rejects a mismatched subject', async () => {
  const data = {
    userId: '22',
    username: '同学',
    bio: '简介',
    level: 2,
    receivedLikes: 4,
    email: 'private@example.test',
    experience: 100,
  }
  const get = vi.spyOn(httpClient, 'get').mockResolvedValue({ data: { success: true, data } })
  const signal = new AbortController().signal
  const result = await fetchPublicProfile('22', 'session', signal)
  expect(get).toHaveBeenCalledWith('/api/users/22/profile', {
    headers: { Authorization: 'Bearer session' },
    signal,
  })
  expect(result).not.toHaveProperty('email')
  expect(result).not.toHaveProperty('experience')
  await expect(fetchPublicProfile('33', 'session', signal)).rejects.toThrow('无法确认')
})
