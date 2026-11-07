import { afterEach, describe, expect, it, vi } from 'vitest'
import { httpClient } from '@/shared/api/httpClient'
import { fetchOwnProfile, updateOwnBio } from './profileApi'

const profile = {
  userId: '42',
  username: '同学',
  maskedEmail: 's***@std.uestc.edu.cn',
  bio: '喜欢面食',
}
afterEach(() => vi.restoreAllMocks())
/** 私有资料传输固定身份，拒绝原始邮箱或不一致回执，不让未知响应字段进入页面状态。 */
describe('本人资料传输', () => {
  it('查询不发送可伪造的账号参数，仅保留白名单字段', async () => {
    const get = vi
      .spyOn(httpClient, 'get')
      .mockResolvedValue({
        data: { success: true, data: { ...profile, privateField: '不应复制' } },
      })
    const signal = new AbortController().signal
    expect(await fetchOwnProfile('42', 'fixed-token', signal)).toEqual(profile)
    expect(get).toHaveBeenCalledExactlyOnceWith('/api/me/profile', {
      signal,
      headers: { Authorization: 'Bearer fixed-token' },
    })
  })
  it('只提交bio，复用固定凭证并校验保存结果', async () => {
    const patch = vi
      .spyOn(httpClient, 'patch')
      .mockResolvedValue({ data: { success: true, data: profile } })
    const signal = new AbortController().signal
    expect(await updateOwnBio('42', 'fixed-token', '喜欢面食', signal)).toEqual(profile)
    expect(patch).toHaveBeenCalledExactlyOnceWith(
      '/api/me/profile',
      { bio: '喜欢面食' },
      { signal, headers: { Authorization: 'Bearer fixed-token' } },
    )
  })
  it.each(['identity', 'email', 'length'])('拒绝不安全或无法确认的响应 %s', async (kind) => {
    const value = { ...profile }
    if (kind === 'identity') value.userId = '99'
    if (kind === 'email') value.maskedEmail = 'raw_email@std.uestc.edu.cn'
    if (kind === 'length') value.bio = '中'.repeat(101)
    vi.spyOn(httpClient, 'get').mockResolvedValue({ data: { success: true, data: value } })
    await expect(
      fetchOwnProfile('42', 'fixed-token', new AbortController().signal),
    ).rejects.toThrow('无法确认')
  })
  it('不把不一致或失败的保存回执当成成功', async () => {
    vi.spyOn(httpClient, 'patch')
      .mockResolvedValueOnce({ data: { success: true, data: profile } })
      .mockRejectedValueOnce(new Error('网络失败'))
    await expect(
      updateOwnBio('42', 'fixed-token', '新简介', new AbortController().signal),
    ).rejects.toThrow('保存结果无法确认')
    await expect(
      updateOwnBio('42', 'fixed-token', '新简介', new AbortController().signal),
    ).rejects.toThrow('网络失败')
  })
})
