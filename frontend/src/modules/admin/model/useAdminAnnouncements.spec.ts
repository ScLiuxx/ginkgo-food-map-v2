import { createApp, h, nextTick, type App } from 'vue'
import { createPinia, setActivePinia, disposePinia } from 'pinia'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { useLoginStore } from '@/modules/auth'
import { clearAccessToken } from '@/shared/api/authSession'
import { useAdminAnnouncements } from './useAdminAnnouncements'
import {
  fetchAdminAnnouncements,
  publishAnnouncement,
  withdrawAnnouncement,
  type AnnouncementDetail,
} from '@/modules/announcement'
vi.mock('@/modules/announcement', () => ({
  fetchAdminAnnouncements: vi.fn(),
  fetchAdminAnnouncement: vi.fn(),
  publishAnnouncement: vi.fn(),
  withdrawAnnouncement: vi.fn(),
  normalizeAnnouncementText: (s: string) => s.replace(/\r\n?/g, '\n').trim(),
}))
const receipt = (): AnnouncementDetail => ({
  id: '12',
  title: '标题',
  content: '正文',
  status: 'PUBLISHED',
  publishedBy: '22',
  publishedAt: '2026-11-08T05:00:00Z',
  withdrawnBy: null,
  withdrawnAt: null,
  replayed: false,
})
const settle = async () => {
  for (let i = 0; i < 12; i++) await nextTick()
}
describe('公告管理状态', () => {
  let state: ReturnType<typeof useAdminAnnouncements>,
    app: App,
    host: HTMLDivElement,
    pinia: ReturnType<typeof createPinia>
  function login(id = 22) {
    useLoginStore().acceptSession({
      accessToken: 'session-' + id,
      tokenType: 'Bearer',
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id, username: 'admin' + id, roles: ['ROLE_ADMIN'] },
    })
  }
  beforeEach(() => {
    vi.resetAllMocks()
    clearAccessToken()
    pinia = createPinia()
    setActivePinia(pinia)
    login()
    host = document.createElement('div')
    document.body.append(host)
    app = createApp({
      setup() {
        state = useAdminAnnouncements()
        return () => h('div')
      },
    }).use(pinia)
    app.mount(host)
    state.title.value = '标题'
    state.content.value = '正文'
    vi.mocked(fetchAdminAnnouncements).mockResolvedValue({
      records: [],
      total: 0,
      totalPages: 0,
      page: 1,
      pageSize: 20,
    })
  })
  afterEach(() => {
    app.unmount()
    disposePinia(pinia)
    host.remove()
    clearAccessToken()
    vi.restoreAllMocks()
  })
  it('未知发布结果保留同一UUID及正文，重试成功后读取记录', async () => {
    vi.mocked(publishAnnouncement)
      .mockRejectedValueOnce(new Error('网络中断'))
      .mockResolvedValueOnce(receipt())
    expect(await state.publish()).toBe(false)
    const pending = { ...state.pending.value! }
    expect(state.title.value).toBe('标题')
    expect(await state.publish()).toBe(true)
    expect(vi.mocked(publishAnnouncement).mock.calls[0]![0]).toEqual(pending)
    expect(vi.mocked(publishAnnouncement).mock.calls[1]![0]).toEqual(pending)
    expect(state.pending.value).toBeNull()
    expect(state.tab.value).toBe('records')
    expect(state.title.value).toBe('')
    expect(state.notice.value).toBe('公告已发布。')
  })
  it('发布成功但记录读取失败，仅重试读取，不能再发一条公告', async () => {
    vi.mocked(publishAnnouncement).mockResolvedValue(receipt())
    vi.mocked(fetchAdminAnnouncements).mockRejectedValueOnce(new Error('列表故障'))
    expect(await state.publish()).toBe(true)
    expect(state.notice.value).toBe('公告已发布。')
    expect(state.error.value).toBe('列表故障')
    expect(state.pending.value).toBeNull()
    await state.load()
    expect(publishAnnouncement).toHaveBeenCalledTimes(1)
    expect(state.error.value).toBe('')
  })
  it('重复点击不会并发发布，换号取消并忽略迟到的成功回执', async () => {
    let resolve!: (value: AnnouncementDetail) => void
    vi.mocked(publishAnnouncement).mockReturnValue(
      new Promise((r) => {
        resolve = r
      }),
    )
    const first = state.publish()
    expect(await state.publish()).toBe(false)
    const signal = vi.mocked(publishAnnouncement).mock.calls[0]![2]
    login(33)
    expect(signal.aborted).toBe(true)
    resolve(receipt())
    expect(await first).toBe(false)
    expect(state.tab.value).toBe('publish')
    expect(state.notice.value).toBe('')
    expect(state.title.value).toBe('')
  })
  it('撤回成功后的刷新失败不重复提交撤回', async () => {
    vi.mocked(withdrawAnnouncement).mockResolvedValue({
      ...receipt(),
      status: 'WITHDRAWN',
      withdrawnBy: '22',
      withdrawnAt: '2026-11-08T06:00:00Z',
    })
    vi.mocked(fetchAdminAnnouncements).mockRejectedValueOnce(new Error('暂时不可读'))
    expect(await state.withdraw('12')).toBe(true)
    expect(state.notice.value).toBe('公告已撤回。')
    await state.load()
    expect(withdrawAnnouncement).toHaveBeenCalledTimes(1)
  })
  it('空白和超长内容不能发布，码点长度正确处理表情', async () => {
    state.title.value = ' '
    expect(await state.publish()).toBe(false)
    state.title.value = '中'.repeat(121)
    expect(await state.publish()).toBe(false)
    state.title.value = '😀'.repeat(120)
    expect(state.validForm.value).toBe(true)
    state.content.value = '中'.repeat(10001)
    expect(await state.publish()).toBe(false)
    expect(publishAnnouncement).not.toHaveBeenCalled()
  })
  it('状态筛选与切换记录重新读取第一页', async () => {
    state.tab.value = 'records'
    await settle()
    state.page.value = 3
    state.status.value = 'WITHDRAWN'
    await settle()
    expect(vi.mocked(fetchAdminAnnouncements).mock.lastCall?.slice(0, 2)).toEqual(['WITHDRAWN', 1])
  })
})
