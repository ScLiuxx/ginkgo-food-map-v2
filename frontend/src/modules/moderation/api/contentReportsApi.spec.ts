import { afterEach, describe, expect, it, vi } from 'vitest'
import { httpClient } from '@/shared/api/httpClient'
import {
  dismissContentReport,
  fetchContentReport,
  fetchContentReports,
  removeContentReport,
  submitContentReport,
  type ContentReport,
  type ContentReportPage,
} from './contentReportsApi'

const report = (): ContentReport => ({
  id: '11',
  targetType: 'REVIEW',
  targetId: '301',
  reviewId: '301',
  reporterUserId: '42',
  targetAuthorUserId: '88',
  contentSnapshot: '被举报的点评正文',
  reason: 'ABUSE',
  detail: '包含人身攻击',
  status: 'PENDING',
  handledBy: null,
  handledAt: null,
  decisionNote: null,
  createdAt: '2026-11-10T01:00:00Z',
})
const page = (): ContentReportPage => ({
  records: [report()],
  total: 1,
  totalPages: 1,
  page: 1,
  pageSize: 20,
})
afterEach(() => vi.restoreAllMocks())

/** 举报提交严格区分点评与回复路径，重复举报按成功处理。 */
describe('举报提交接口', () => {
  it('按目标类型选择固定路径并校验回执归属', async () => {
    const post = vi.spyOn(httpClient, 'post').mockResolvedValue({
      data: {
        success: true,
        data: {
          reportId: '11',
          targetType: 'REVIEW',
          targetId: '301',
          status: 'PENDING',
          duplicate: false,
          createdAt: '2026-11-10T01:00:00Z',
        },
      },
    })
    const signal = new AbortController().signal
    await expect(
      submitContentReport(
        'REVIEW',
        '301',
        { reason: 'SPAM', detail: '  广告  ' },
        'token',
        signal,
      ),
    ).resolves.toMatchObject({ reportId: '11', duplicate: false })
    expect(post).toHaveBeenLastCalledWith(
      '/api/reviews/301/reports',
      { reason: 'SPAM', detail: '广告' },
      { signal, headers: { Authorization: 'Bearer token' } },
    )

    post.mockResolvedValueOnce({
      data: {
        success: true,
        data: {
          reportId: '12',
          targetType: 'REPLY',
          targetId: '501',
          status: 'DISMISSED',
          duplicate: true,
          createdAt: '2026-11-10T01:00:00Z',
        },
      },
    })
    await expect(
      submitContentReport('REPLY', '501', { reason: 'OTHER' }, 'token', signal),
    ).resolves.toMatchObject({ status: 'DISMISSED', duplicate: true })
    expect(post).toHaveBeenLastCalledWith(
      '/api/review-replies/501/reports',
      { reason: 'OTHER', detail: null },
      { signal, headers: { Authorization: 'Bearer token' } },
    )
  })
  it('拒绝越权目标、超长说明和归属不符的首次回执', async () => {
    const post = vi.spyOn(httpClient, 'post').mockResolvedValue({
      data: {
        success: true,
        data: {
          reportId: '11',
          targetType: 'REVIEW',
          targetId: '999',
          status: 'PENDING',
          duplicate: false,
          createdAt: '2026-11-10T01:00:00Z',
        },
      },
    })
    const signal = new AbortController().signal
    await expect(
      submitContentReport('REVIEW', '../301', { reason: 'SPAM' }, 'token', signal),
    ).rejects.toThrow('无法确认')
    await expect(
      submitContentReport('REVIEW', '301', { reason: 'SPAM', detail: '字'.repeat(201) }, 'token', signal),
    ).rejects.toThrow('无法确认')
    await expect(
      submitContentReport('REVIEW', '301', { reason: 'SPAM' }, 'token', signal),
    ).rejects.toThrow('无法确认')
    expect(post).toHaveBeenCalledTimes(1)
  })
})

/** 管理端分页依赖页码、页大小和状态筛选三重一致，避免错页或串状态。 */
describe('管理端举报查询', () => {
  it('只投影白名单字段并固定分页参数', async () => {
    const get = vi.spyOn(httpClient, 'get').mockResolvedValue({
      data: { success: true, data: { ...page(), privateHash: 'hidden' } },
    })
    const signal = new AbortController().signal
    const result = await fetchContentReports('PENDING', 1, 'admin-token', signal)
    expect(result.records[0]).toEqual(report())
    expect(get).toHaveBeenCalledWith('/api/admin/content-reports', {
      params: { status: 'PENDING', page: 1, pageSize: 20 },
      signal,
      headers: { Authorization: 'Bearer admin-token' },
    })
  })
  it('接受已处置明细并拒绝分页关系或状态字段矛盾', async () => {
    const get = vi
      .spyOn(httpClient, 'get')
      .mockResolvedValue({ data: { success: true, data: report() } })
    const signal = new AbortController().signal
    const handled: ContentReport = {
      ...report(),
      status: 'RESOLVED',
      handledBy: '1',
      handledAt: '2026-11-10T02:00:00Z',
      decisionNote: '确认违规',
    }
    get.mockResolvedValueOnce({ data: { success: true, data: handled } })
    await expect(fetchContentReport('11', 'token', signal)).resolves.toEqual(handled)

    get.mockResolvedValueOnce({
      data: { success: true, data: { ...page(), total: 21, totalPages: 1 } },
    })
    await expect(fetchContentReports('PENDING', 1, 'token', signal)).rejects.toThrow('无法确认')

    get.mockResolvedValueOnce({
      data: { success: true, data: { ...page(), records: [{ ...report(), handledBy: '1' }] } },
    })
    await expect(fetchContentReports('PENDING', 1, 'token', signal)).rejects.toThrow('无法确认')
  })
})

/** 处置回执必须与请求举报一致，并返回真实影响范围。 */
describe('管理端举报处置', () => {
  it('驳回与删除使用固定路径，备注为空时显式传null', async () => {
    const post = vi
      .spyOn(httpClient, 'post')
      .mockResolvedValueOnce({
        data: {
          success: true,
          data: {
            reportId: '11',
            status: 'DISMISSED',
            affectedReports: 2,
            handledAt: '2026-11-10T02:00:00Z',
          },
        },
      })
      .mockResolvedValueOnce({
        data: {
          success: true,
          data: {
            reportId: '11',
            targetType: 'REVIEW',
            targetId: '301',
            status: 'RESOLVED',
            affectedReports: 2,
            contentDeleted: true,
            handledAt: '2026-11-10T02:00:00Z',
          },
        },
      })
    const signal = new AbortController().signal
    await expect(dismissContentReport('11', null, 'token', signal)).resolves.toMatchObject({
      affectedReports: 2,
    })
    await expect(removeContentReport('11', '确认违规', 'token', signal)).resolves.toMatchObject({
      contentDeleted: true,
    })
    expect(post).toHaveBeenNthCalledWith(1, '/api/admin/content-reports/11/dismiss', null, {
      signal,
      headers: { Authorization: 'Bearer token' },
    })
    expect(post).toHaveBeenNthCalledWith(
      2,
      '/api/admin/content-reports/11/remove',
      { note: '确认违规' },
      { signal, headers: { Authorization: 'Bearer token' } },
    )
  })
  it('拒绝备注超长与状态错误的回执', async () => {
    const post = vi.spyOn(httpClient, 'post').mockResolvedValue({
      data: {
        success: true,
        data: {
          reportId: '11',
          status: 'PENDING',
          affectedReports: 1,
          handledAt: '2026-11-10T02:00:00Z',
        },
      },
    })
    const signal = new AbortController().signal
    await expect(
      dismissContentReport('11', '字'.repeat(201), 'token', signal),
    ).rejects.toThrow('无法确认')
    await expect(dismissContentReport('11', null, 'token', signal)).rejects.toThrow('无法确认')
    expect(post).toHaveBeenCalledTimes(1)
  })
})
