import { createApp, nextTick, ref, type App, type Ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AdminActivityWorkspace from './AdminActivityWorkspace.vue'
import { useAdminActivityStatistics } from '../model/useAdminActivityStatistics'
import { activityStatisticsFixture } from '../test/activityStatisticsFixture'
import type { ActivityStatistics } from '../model/activityStatistics'

vi.mock('../model/useAdminActivityStatistics', () => ({ useAdminActivityStatistics: vi.fn() }))
vi.mock('./AdminHeader.vue', () => ({ default: { template: '<header />' } }))
vi.mock('./AdminSidebar.vue', () => ({ default: { template: '<nav />' } }))

describe('使用统计工作区', () => {
  let app: App, host: HTMLDivElement
  let data: Ref<ActivityStatistics | null>
  let error: Ref<string>
  const load = vi.fn()
  beforeEach(() => {
    vi.resetAllMocks()
    data = ref(activityStatisticsFixture())
    error = ref('')
    vi.mocked(useAdminActivityStatistics).mockReturnValue({
      data,
      error,
      load,
      loading: ref(false),
      unavailable: ref(false),
    })
    host = document.createElement('div')
    document.body.append(host)
    app = createApp(AdminActivityWorkspace, {
      username: '管理员',
      loggingOut: false,
      sessionError: null,
    })
    app.mount(host)
  })
  afterEach(() => {
    app.unmount()
    host.remove()
  })
  it('数字按协议展示，切换图表不重新读取，单位与键盘标签同步', async () => {
    expect([...host.querySelectorAll('.activity-metric-number')].map((n) => n.textContent)).toEqual(
      ['128截至当前', '436', '12', '37'],
    )
    const tabs = host.querySelectorAll<HTMLButtonElement>('[role="tab"]')
    tabs[2]!.click()
    await nextTick()
    expect(host.querySelector('.activity-axis-unit')?.textContent).toBe('条')
    expect(tabs[2]!.getAttribute('aria-selected')).toBe('true')
    tabs[2]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }))
    await nextTick()
    expect(tabs[1]!.getAttribute('aria-selected')).toBe('true')
    expect(host.querySelector('.activity-axis-unit')?.textContent).toBe('人')
    expect(load).not.toHaveBeenCalled()
  })
  it('没有接口数据时四项保持未知，失败可重试，不出现示例数字', async () => {
    data.value = null
    error.value = '暂时无法读取统计数据'
    await nextTick()
    expect([...host.querySelectorAll('.activity-metric-number')].map((n) => n.textContent)).toEqual(
      ['—', '—', '—', '—'],
    )
    expect(host.querySelector('[role="tabpanel"]')?.textContent).toContain('暂时无法读取统计数据')
    host.querySelector<HTMLButtonElement>('.activity-state button')!.click()
    expect(load).toHaveBeenCalledOnce()
    expect(host.querySelector('.activity-plot')).toBeNull()
  })
  it('启用首日只显示已有数据，未知日期留空且真实0仍展示', async () => {
    const fixture = activityStatisticsFixture()
    fixture.startedAt = '2026-11-19T06:00:00Z'
    fixture.days = [
      {
        ...fixture.days[29]!,
        activeUserCount: null,
        activeSyncedAt: null,
        newUserCount: 0,
        collectionStatus: 'PARTIAL',
      },
    ]
    fixture.sevenDayCollectionStatus = 'PARTIAL'
    data.value = fixture
    await nextTick()
    expect([...host.querySelectorAll('.activity-metric-number')].map((n) => n.textContent)).toEqual(
      ['—', '436', '0', '37'],
    )
    expect(host.querySelector('.activity-chart-empty')?.textContent).toBe('暂无可展示的数据')
    expect(host.querySelector('tbody tr')?.textContent).toContain('未采集')
    expect(host.querySelector('.activity-data-origin')?.textContent).toContain('2026.11.19 14:00')
  })
  it('更新失败保留数字和原更新时间，图表支持键盘查看每天数值', async () => {
    error.value = '读取失败'
    await nextTick()
    expect(host.querySelector('.activity-update-error')?.textContent).toContain('当前显示上次数据')
    expect(host.querySelector('.activity-updated')?.textContent).toContain('14:32')
    const plot = host.querySelector<SVGSVGElement>('.activity-plot')!
    plot.dispatchEvent(new FocusEvent('focus'))
    plot.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }))
    await nextTick()
    expect(host.querySelector('.activity-tooltip-date')?.textContent).toBe('11月18日')
    plot.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await nextTick()
    expect(host.querySelector('.activity-tooltip')).toBeNull()
  })
  it('触摸选中的日期不会被随后获得焦点或触摸离开覆盖', async () => {
    const plot = host.querySelector<SVGSVGElement>('.activity-plot')!
    vi.spyOn(plot, 'getBoundingClientRect').mockReturnValue({ left: 0, width: 950 } as DOMRect)
    const down = new MouseEvent('pointerdown', { clientX: 36, bubbles: true })
    Object.defineProperty(down, 'pointerType', { value: 'touch' })
    plot.dispatchEvent(down)
    plot.dispatchEvent(new FocusEvent('focus'))
    const leave = new Event('pointerleave')
    Object.defineProperty(leave, 'pointerType', { value: 'touch' })
    plot.dispatchEvent(leave)
    await nextTick()
    expect(host.querySelector('.activity-tooltip-date')?.textContent).toBe('10月21日')
  })
})
