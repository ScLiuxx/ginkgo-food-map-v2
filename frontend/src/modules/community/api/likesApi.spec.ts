import { afterEach, describe, it, expect, vi } from 'vitest'
import { httpClient } from '@/shared/api/httpClient'
import { fetchLike, setLike } from './likesApi'
const row = (id: string, liked = false) => ({
  targetId: id,
  count: liked ? 1 : 0,
  liked,
  available: true,
})
afterEach(() => vi.restoreAllMocks())
/** 身份合批、服务端回执校验和固定状态写入契约。 */
describe('点赞接口', () => {
  it('同身份同类型合批且保留大ID', async () => {
    const ids = ['9007199254740997', '2'],
      get = vi.spyOn(httpClient, 'get').mockResolvedValue({
        data: {
          success: true,
          data: { userId: '42', type: 'REVIEW', records: ids.map((id) => row(id)) },
        },
      })
    const signal = new AbortController().signal
    expect(
      await Promise.all(ids.map((id) => fetchLike('REVIEW', id, '42', 'token', signal))),
    ).toEqual(ids.map((id) => row(id)))
    expect(get).toHaveBeenCalledTimes(1)
    expect(get.mock.calls[0]![1]?.headers).toEqual({ Authorization: 'Bearer token' })
  })
  it('不同身份不混批，错误身份回执不能进入页面', async () => {
    const get = vi.spyOn(httpClient, 'get').mockResolvedValue({
      data: { success: true, data: { userId: '42', type: 'REVIEW', records: [row('1')] } },
    })
    const a = fetchLike('REVIEW', '1', '42', 'a', new AbortController().signal),
      b = fetchLike('REVIEW', '1', '88', 'b', new AbortController().signal)
    const values = await Promise.allSettled([a, b])
    expect(values[0]!.status).toBe('fulfilled')
    expect(values[1]!.status).toBe('rejected')
    expect(get).toHaveBeenCalledTimes(2)
  })
  it('取消的批量消费者拒绝迟到响应', async () => {
    vi.spyOn(httpClient, 'get').mockResolvedValue({
      data: { success: true, data: { userId: null, type: 'REVIEW', records: [row('1')] } },
    })
    const abort = new AbortController(),
      promise = fetchLike('REVIEW', '1', null, null, abort.signal)
    abort.abort()
    await expect(promise).rejects.toThrow('取消')
  })
  it.each(['wrongId', 'negative', 'wrongBoolean'])('拒绝损坏回执%s', async (kind) => {
    const value = row('1')
    if (kind === 'wrongId') value.targetId = '2'
    if (kind === 'negative') value.count = -1
    if (kind === 'wrongBoolean') value.liked = 'yes' as unknown as boolean
    vi.spyOn(httpClient, 'put').mockResolvedValue({ data: { success: true, data: value } })
    await expect(
      setLike('REVIEW', '1', true, 'token', new AbortController().signal),
    ).rejects.toThrow('无法确认')
  })
  it('取消使用固定DELETE路径并返回权威状态', async () => {
    const remove = vi
      .spyOn(httpClient, 'delete')
      .mockResolvedValue({ data: { success: true, data: row('9007199254740997') } })
    expect(
      await setLike('REPLY', '9007199254740997', false, 'token', new AbortController().signal),
    ).toEqual(row('9007199254740997'))
    expect(remove.mock.calls[0]![0]).toBe('/api/review-replies/9007199254740997/like')
  })
})
