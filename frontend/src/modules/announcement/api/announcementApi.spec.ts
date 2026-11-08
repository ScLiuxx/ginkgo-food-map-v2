import { describe, it, expect, vi, afterEach } from 'vitest'
import { httpClient } from '@/shared/api/httpClient'
import {
  fetchAnnouncement,
  fetchAdminAnnouncements,
  publishAnnouncement,
  withdrawAnnouncement,
  type AnnouncementDetail,
} from './announcementApi'
const row = (): AnnouncementDetail => ({
  id: '9007199254740993',
  title: '标题',
  content: '纯文本<script>alert(1)</script>',
  status: 'PUBLISHED',
  publishedBy: '22',
  publishedAt: '2026-11-08T05:00:00Z',
  withdrawnBy: null,
  withdrawnAt: null,
  replayed: false,
})
afterEach(() => vi.restoreAllMocks())
describe('公告API白名单', () => {
  it('用户详情绑定本人凭证并固定用户路径，保持纯文本和大整数ID', async () => {
    const get = vi
      .spyOn(httpClient, 'get')
      .mockResolvedValue({ data: { success: true, data: { ...row(), unexpected: 'secret' } } })
    const signal = new AbortController().signal
    const value = await fetchAnnouncement(row().id, 'user-token', signal)
    expect(get).toHaveBeenCalledWith('/api/me/announcements/' + row().id, {
      signal,
      headers: { Authorization: 'Bearer user-token' },
    })
    expect(value.content).toContain('<script>')
    expect(value).not.toHaveProperty('unexpected')
  })
  it('拒绝用户侧撤回详情及不匹配ID', async () => {
    const get = vi
      .spyOn(httpClient, 'get')
      .mockResolvedValue({
        data: {
          success: true,
          data: {
            ...row(),
            status: 'WITHDRAWN',
            withdrawnBy: '22',
            withdrawnAt: '2026-11-08T06:00:00Z',
          },
        },
      })
    await expect(
      fetchAnnouncement(row().id, 'token', new AbortController().signal),
    ).rejects.toThrow()
    get.mockResolvedValue({ data: { success: true, data: row() } })
    await expect(fetchAnnouncement('12', 'token', new AbortController().signal)).rejects.toThrow()
  })
  it('发布保持请求UUID且核验回执正文，错误回执不能误判成功', async () => {
    const post = vi
      .spyOn(httpClient, 'post')
      .mockResolvedValue({ data: { success: true, data: row() } })
    const input = { clientRequestId: crypto.randomUUID(), title: '标题', content: row().content }
    const signal = new AbortController().signal
    await publishAnnouncement(input, 'admin-token', signal)
    expect(post).toHaveBeenCalledWith('/api/admin/announcements', input, {
      signal,
      headers: { Authorization: 'Bearer admin-token' },
    })
    await expect(
      publishAnnouncement({ ...input, content: '其他正文' }, 'admin-token', signal),
    ).rejects.toThrow()
  })
  it('撤回仅接受相同ID的WITHDRAWN回执', async () => {
    const post = vi
      .spyOn(httpClient, 'post')
      .mockResolvedValue({ data: { success: true, data: row() } })
    const signal = new AbortController().signal
    await expect(withdrawAnnouncement(row().id, 'token', signal)).rejects.toThrow()
    post.mockResolvedValue({
      data: {
        success: true,
        data: {
          ...row(),
          status: 'WITHDRAWN',
          withdrawnBy: '22',
          withdrawnAt: '2026-11-08T06:00:00Z',
        },
      },
    })
    await expect(withdrawAnnouncement(row().id, 'token', signal)).resolves.toHaveProperty(
      'status',
      'WITHDRAWN',
    )
  })
  it('管理分页拒绝状态不符或计数损坏，且拒绝非法ID', async () => {
    vi.spyOn(httpClient, 'get').mockResolvedValue({
      data: {
        success: true,
        data: { records: [row()], total: 1, page: 1, pageSize: 20, totalPages: 1 },
      },
    })
    await expect(
      fetchAdminAnnouncements('WITHDRAWN', 1, 'token', new AbortController().signal),
    ).rejects.toThrow()
    for (const id of ['0', '01', '9223372036854775808', 'announcement:12'])
      await expect(fetchAnnouncement(id, 'token', new AbortController().signal)).rejects.toThrow()
  })
})
