import { createApp, h, nextTick, type App } from 'vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { AxiosError } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useLoginStore } from '@/modules/auth'
import {
  dismissContentReport,
  fetchContentReports,
  removeContentReport,
  type ContentReport,
  type ContentReportPage,
} from '@/modules/moderation'
import { useAdminContentReports } from './useAdminContentReports'

vi.mock('@/modules/moderation', () => ({
  fetchContentReports: vi.fn(),
  fetchContentReport: vi.fn(),
  dismissContentReport: vi.fn(),
  removeContentReport: vi.fn(),
}))

const report = (): ContentReport => ({
  id: '11',
  targetType: 'REVIEW',
  targetId: '301',
  reviewId: '301',
  reporterUserId: '42',
  targetAuthorUserId: '88',
  contentSnapshot: '被举报的点评正文',
  reason: 'ABUSE',
  detail: null,
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
const settle = async () => {
  for (let i = 0; i < 10; i++) await nextTick()
}
let app: App | undefined, pinia: Pinia
beforeEach(() => {
  vi.mocked(fetchContentReports).mockReset().mockResolvedValue(page())
  vi.mocked(dismissContentReport).mockReset()
  vi.mocked(removeContentReport).mockReset()
  pinia = createPinia()
  setActivePinia(pinia)
  useLoginStore().acceptSession({
    accessToken: 'admin-token',
    tokenType: 'Bearer',
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id: 1, username: '管理员', roles: ['ROLE_ADMIN'] },
  })
})
afterEach(() => {
  app?.unmount()
  app = undefined
})
async function mountState() {
  let state!: ReturnType<typeof useAdminContentReports>
  const host = document.createElement('div')
  document.body.append(host)
  app = createApp({
    setup() {
      state = useAdminContentReports()
      return () => h('div')
    },
  })
  app.use(pinia)
  app.mount(host)
  await settle()
  return state
}

/** 管理端状态必须绑定当前管理员令牌，处置成功后用服务端真实影响范围提示。 */
describe('举报处理状态', () => {
  it('加载当前筛选页并绑定管理员凭证', async () => {
    const state = await mountState()
    await state.load(1)
    expect(fetchContentReports).toHaveBeenCalledWith(
      'PENDING',
      1,
      'admin-token',
      expect.any(AbortSignal),
    )
    expect(state.result.value?.records[0]?.contentSnapshot).toContain('被举报')
    expect(state.page.value).toBe(1)
  })

  it('驳回成功展示affectedReports并刷新列表', async () => {
    vi.mocked(dismissContentReport).mockResolvedValue({
      reportId: '11',
      status: 'DISMISSED',
      affectedReports: 3,
      handledAt: '2026-11-10T02:00:00Z',
    })
    const state = await mountState()
    state.detail.value = report()
    await expect(state.dismiss('11', '  误报  ')).resolves.toBe('ok')
    expect(dismissContentReport).toHaveBeenCalledWith(
      '11',
      '误报',
      'admin-token',
      expect.any(AbortSignal),
    )
    expect(state.notice.value).toContain('共处理 3 条举报')
    expect(state.detail.value).toBeNull()
    expect(fetchContentReports).toHaveBeenCalledTimes(1)
  })

  it('409视为已被他人处理，刷新列表且不保留旧详情', async () => {
    const conflict = new AxiosError('conflict')
    conflict.response = { status: 409 } as NonNullable<AxiosError['response']>
    vi.mocked(removeContentReport).mockRejectedValue(conflict)
    const state = await mountState()
    state.detail.value = report()
    await expect(state.remove('11', '')).resolves.toBe('conflict')
    expect(state.actionError.value).toContain('已被其他管理员处理')
    expect(state.detail.value).toBeNull()
    expect(fetchContentReports).toHaveBeenCalledTimes(1)
  })

  it('备注超长在本地拒绝，不发起处置请求', async () => {
    const state = await mountState()
    await expect(state.dismiss('11', '字'.repeat(201))).resolves.toBe('failed')
    expect(state.actionError.value).toContain('最多200字')
    expect(dismissContentReport).not.toHaveBeenCalled()
  })
})
