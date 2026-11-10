import { createApp, h, nextTick, type App } from 'vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useLoginStore } from '@/modules/auth'
import {
  fetchContentReport,
  fetchContentReports,
  removeContentReport,
  type ContentReport,
  type ContentReportPage,
} from '@/modules/moderation'
import AdminReportsWorkspace from './AdminReportsWorkspace.vue'

vi.mock('@/modules/admin/api/restaurantRequestsApi', () => ({
  fetchRequestPage: vi.fn().mockResolvedValue({ counts: { PENDING: 0 } }),
}))
vi.mock('@/modules/moderation/api/contentReportsApi', () => ({
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
const settle = async () => {
  for (let i = 0; i < 20; i++) await nextTick()
}
let app: App | undefined, pinia: Pinia
beforeEach(() => {
  vi.mocked(fetchContentReports).mockReset().mockResolvedValue(page())
  vi.mocked(fetchContentReport).mockReset().mockResolvedValue(report())
  vi.mocked(removeContentReport).mockReset()
  pinia = createPinia()
  setActivePinia(pinia)
  useLoginStore().acceptSession({
    accessToken: 'admin-token',
    tokenType: 'Bearer',
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id: 1, username: '管理员', roles: ['ROLE_ADMIN'] },
  })
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
    configurable: true,
    value() {
      this.open = true
    },
  })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', {
    configurable: true,
    value() {
      this.open = false
    },
  })
})
afterEach(() => {
  app?.unmount()
  app = undefined
  document.body.innerHTML = ''
  delete (HTMLDialogElement.prototype as unknown as Record<string, unknown>).showModal
  delete (HTMLDialogElement.prototype as unknown as Record<string, unknown>).close
})
async function mountWorkspace() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/admin/reports',
        component: AdminReportsWorkspace,
        props: { username: '管理员', loggingOut: false, sessionError: null },
      },
      { path: '/admin/reviews', component: { template: '<p>审核</p>' } },
    ],
  })
  await router.push('/admin/reports')
  await router.isReady()
  const host = document.createElement('div')
  document.body.append(host)
  app = createApp({ render: () => h(RouterView) })
  app.use(pinia)
  app.use(router)
  app.mount(host)
  await settle()
  return host
}
const click = (host: HTMLElement, label: string) => {
  const button = [...host.querySelectorAll<HTMLButtonElement>('button')].find(
    (value) => value.textContent?.trim() === label,
  )
  expect(button, label).toBeDefined()
  button!.click()
}

/** 列表、详情与删除二次确认必须共用同一份服务端举报数据。 */
describe('管理端举报页', () => {
  it('展示待处理举报，详情经二次确认后删除并显示结案范围', async () => {
    vi.mocked(removeContentReport).mockResolvedValue({
      reportId: '11',
      targetType: 'REVIEW',
      targetId: '301',
      status: 'RESOLVED',
      affectedReports: 2,
      contentDeleted: true,
      handledAt: '2026-11-10T02:00:00Z',
    })
    const host = await mountWorkspace()
    expect(host.textContent).toContain('举报处理')
    expect(host.textContent).toContain('被举报的点评正文')
    expect(host.textContent).toContain('辱骂或人身攻击')

    click(host, '查看详情')
    await settle()
    expect(document.querySelector('.report-dialog')?.textContent).toContain('包含人身攻击')
    expect(fetchContentReport).toHaveBeenCalledWith(
      '11',
      'admin-token',
      expect.any(AbortSignal),
    )

    click(document.body, '确认违规并删除')
    await settle()
    expect(document.querySelector('.report-confirm')?.textContent).toContain(
      '删除后无法恢复',
    )
    click(document.body, '永久删除并结案')
    await settle()
    expect(removeContentReport).toHaveBeenCalledWith(
      '11',
      null,
      'admin-token',
      expect.any(AbortSignal),
    )
    expect(host.textContent).toContain('内容已永久删除，共结案 2 条举报')
    expect(fetchContentReports).toHaveBeenCalledTimes(2)
    expect(document.querySelector<HTMLDialogElement>('.report-dialog')?.open).toBe(false)
  })
})
