import { createApp, h, nextTick, ref, type App } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MerchantPlacePicker from './MerchantPlacePicker.vue'
import { searchAMapPlaces } from '../sdk/places'
import type { AMapPlace } from '../sdk/places'

vi.mock('../sdk/places', () => ({ searchAMapPlaces: vi.fn() }))

/** 选择器覆盖异步失效、主动搜索与错误恢复；不使用真实高德配额。 */
describe('高德商户位置选择', () => {
  let app: App, host: HTMLDivElement
  const select = vi.fn()
  const place: AMapPlace = {
    id: 'poi1',
    name: '测试店(沙河店)',
    address: '建设路1号',
    district: '成都',
    category: '火锅',
    longitude: 104.1,
    latitude: 30.6,
  }
  const settle = async () => {
    for (let i = 0; i < 10; i++) await nextTick()
  }
  beforeEach(() => {
    vi.resetAllMocks()
    host = document.createElement('div')
    document.body.append(host)
    const name = ref('测试店')
    app = createApp({
      render: () =>
        h(MerchantPlacePicker, {
          modelValue: name.value,
          'onUpdate:modelValue': (value: string) => {
            name.value = value
          },
          onSelect: select,
        }),
    })
    app.mount(host)
  })
  afterEach(() => {
    app.unmount()
    host.remove()
  })
  const search = async () => {
    host.querySelector('button')!.click()
    await settle()
  }
  it('挂载不搜索，选择后只回传目标门店；Enter不会提交外层表单', async () => {
    expect(searchAMapPlaces).not.toHaveBeenCalled()
    vi.mocked(searchAMapPlaces).mockResolvedValue({
      places: [place],
      total: 1,
    })
    const enter = new KeyboardEvent('keydown', {
      key: 'Enter',
      bubbles: true,
      cancelable: true,
    })
    host.querySelector('input')!.dispatchEvent(enter)
    await settle()
    expect(enter.defaultPrevented).toBe(true)
    expect(searchAMapPlaces).toHaveBeenCalledWith(
      '测试店',
      1,
      expect.any(AbortSignal),
    )
    expect(select).not.toHaveBeenCalled()
    host.querySelector<HTMLButtonElement>('li button')!.click()
    expect(select).toHaveBeenCalledWith(place)
  })
  it('改关键词丢弃迟到结果，服务失败可重试，卸载取消请求', async () => {
    let finish!: (value: { places: AMapPlace[]; total: number }) => void
    vi.mocked(searchAMapPlaces).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    await search()
    const signal = vi.mocked(searchAMapPlaces).mock.calls[0]![2]
    const input = host.querySelector('input')!
    input.value = '另一家店'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await settle()
    expect(signal.aborted).toBe(true)
    finish({ places: [place], total: 1 })
    await settle()
    expect(host.querySelector('li')).toBeNull()
    vi.mocked(searchAMapPlaces).mockRejectedValueOnce(new Error('搜索失败'))
    await search()
    expect(host.textContent).toContain('搜索失败')
    vi.mocked(searchAMapPlaces).mockResolvedValueOnce({ places: [], total: 0 })
    await search()
    expect(host.textContent).toContain('没有找到门店')
    expect(host.querySelector('[role="alert"]')).toBeNull()
    vi.mocked(searchAMapPlaces).mockReturnValueOnce(new Promise(() => {}))
    await search()
    const lastSignal = vi.mocked(searchAMapPlaces).mock.calls.at(-1)![2]
    app.unmount()
    expect(lastSignal.aborted).toBe(true)
  })
  it('搜索可翻页，异常范围位置不能被选入商户资料', async () => {
    vi.mocked(searchAMapPlaces).mockResolvedValue({
      places: [place],
      total: 21,
    })
    await search()
    ;[...host.querySelectorAll('button')]
      .find((b) => b.textContent?.trim() === '下一页')!
      .click()
    await settle()
    expect(searchAMapPlaces).toHaveBeenLastCalledWith(
      '测试店',
      2,
      expect.any(AbortSignal),
    )
    vi.mocked(searchAMapPlaces).mockResolvedValue({
      places: [{ ...place, latitude: 1 }],
      total: 1,
    })
    await search()
    host.querySelector<HTMLButtonElement>('li button')!.click()
    expect(select).not.toHaveBeenCalled()
    await settle()
    expect(host.textContent).toContain('不在支持范围')
  })
})
