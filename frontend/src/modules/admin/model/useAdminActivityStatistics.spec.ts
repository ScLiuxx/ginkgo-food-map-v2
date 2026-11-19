import { createApp, h, nextTick, type App } from 'vue'
import { createPinia, disposePinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useLoginStore } from '@/modules/auth'
import { clearAccessToken } from '@/shared/api/authSession'
import { fetchActivityStatistics } from '../api/activityStatisticsApi'
import { activityStatisticsFixture } from '../test/activityStatisticsFixture'
import { useAdminActivityStatistics } from './useAdminActivityStatistics'
import type { ActivityStatistics } from './activityStatistics'

vi.mock('../api/activityStatisticsApi', () => ({ fetchActivityStatistics: vi.fn() }))
const settle = async () => {
  for (let i = 0; i < 8; i++) await nextTick()
}

describe('管理端统计自动读取', () => {
  let app: App, host: HTMLDivElement, pinia: ReturnType<typeof createPinia>
  let state: ReturnType<typeof useAdminActivityStatistics>
  let visibility: ReturnType<typeof vi.spyOn>
  function login(id = 1, admin = true) {
    useLoginStore().acceptSession({
      accessToken: 'activity-' + id,
      tokenType: 'Bearer',
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id, username: 'test' + id, roles: [admin ? 'ROLE_ADMIN' : 'ROLE_USER'] },
    })
  }
  function mount() {
    app = createApp({
      setup() {
        state = useAdminActivityStatistics()
        return () => h('div')
      },
    }).use(pinia)
    app.mount(host)
  }
  beforeEach(() => {
    vi.resetAllMocks()
    vi.useFakeTimers()
    visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    clearAccessToken()
    pinia = createPinia()
    setActivePinia(pinia)
    login()
    vi.mocked(fetchActivityStatistics).mockResolvedValue(activityStatisticsFixture())
    host = document.createElement('div')
    document.body.append(host)
  })
  afterEach(() => {
    app?.unmount()
    disposePinia(pinia)
    host.remove()
    clearAccessToken()
    vi.restoreAllMocks()
    vi.useRealTimers()
  })
  it('进入立即读取，每60秒更新，隐藏暂停、恢复可见立即读取', async () => {
    mount()
    await settle()
    expect(fetchActivityStatistics).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(60_000)
    expect(fetchActivityStatistics).toHaveBeenCalledTimes(2)
    visibility.mockReturnValue('hidden')
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(180_000)
    expect(fetchActivityStatistics).toHaveBeenCalledTimes(2)
    visibility.mockReturnValue('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    await settle()
    expect(fetchActivityStatistics).toHaveBeenCalledTimes(3)
  })
  it('自动更新失败保留数据和原读取时间，重试成功才替换', async () => {
    mount()
    await settle()
    const previous = state.data.value
    vi.mocked(fetchActivityStatistics).mockRejectedValueOnce(new Error('network'))
    await vi.advanceTimersByTimeAsync(60_000)
    expect(state.data.value).toEqual(previous)
    expect(state.data.value?.fetchedAt).toBe('2026-11-19T06:32:00Z')
    expect(state.error.value).toBe('暂时无法读取统计数据')
    vi.mocked(fetchActivityStatistics).mockResolvedValueOnce({
      ...activityStatisticsFixture(),
      fetchedAt: '2026-11-19T06:34:00Z',
    })
    await state.load()
    expect(state.error.value).toBe('')
    expect(state.data.value?.fetchedAt).toBe('2026-11-19T06:34:00Z')
  })
  it('首次失败不制造数据，404明确待开放并可重试', async () => {
    vi.mocked(fetchActivityStatistics).mockRejectedValueOnce({
      isAxiosError: true,
      response: { status: 404 },
    })
    mount()
    await settle()
    expect(state.data.value).toBeNull()
    expect(state.unavailable.value).toBe(true)
    await state.load()
    expect(state.data.value?.enabled).toBe(true)
    expect(state.unavailable.value).toBe(false)
  })
  it('换号取消请求并清除旧结果，迟到响应不能覆盖新管理员', async () => {
    let resolve!: (value: ActivityStatistics) => void
    vi.mocked(fetchActivityStatistics).mockReturnValueOnce(
      new Promise((done) => {
        resolve = done
      }),
    )
    mount()
    const oldSignal = vi.mocked(fetchActivityStatistics).mock.calls[0]![1]
    login(2)
    await settle()
    expect(oldSignal.aborted).toBe(true)
    resolve({ ...activityStatisticsFixture(), sevenDayActiveUserCount: 9999 })
    await settle()
    expect(state.data.value?.sevenDayActiveUserCount).toBe(436)
  })
  it('普通用户或退出后不读取，旧管理员数据立即清空', async () => {
    mount()
    await settle()
    login(2, false)
    await settle()
    expect(state.data.value).toBeNull()
    const calls = vi.mocked(fetchActivityStatistics).mock.calls.length
    await vi.advanceTimersByTimeAsync(120_000)
    expect(fetchActivityStatistics).toHaveBeenCalledTimes(calls)
    clearAccessToken()
    await state.load()
    expect(fetchActivityStatistics).toHaveBeenCalledTimes(calls)
  })
  it('隐藏和卸载均取消在途读取，迟到结果不恢复数据或定时器', async () => {
    let resolve!: (value: ActivityStatistics) => void
    vi.mocked(fetchActivityStatistics).mockReturnValueOnce(
      new Promise((done) => {
        resolve = done
      }),
    )
    mount()
    const signal = vi.mocked(fetchActivityStatistics).mock.calls[0]![1]
    visibility.mockReturnValue('hidden')
    document.dispatchEvent(new Event('visibilitychange'))
    expect(signal.aborted).toBe(true)
    app.unmount()
    resolve(activityStatisticsFixture())
    await settle()
    expect(state.data.value).toBeNull()
    await vi.advanceTimersByTimeAsync(120_000)
    expect(fetchActivityStatistics).toHaveBeenCalledTimes(1)
  })
})
