import { effectScope } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AMapSDK } from '../sdk/types'
import { useMyLocation } from './location'

/** 不调用设备或高德：验证授权、坐标边界和卸载后的迟到回调。 */
describe('用户主动单次定位', () => {
  const scopes: ReturnType<typeof effectScope>[] = []
  function setup() {
    vi.useFakeTimers()
    vi.stubGlobal('isSecureContext', true)
    const getCurrentPosition = vi.fn<Geolocation['getCurrentPosition']>()
    vi.stubGlobal('navigator', { geolocation: { getCurrentPosition } })
    const convertFrom = vi.fn<AMapSDK['convertFrom']>()
    const scope = effectScope()
    scopes.push(scope)
    const state = scope.run(() => useMyLocation(() => ({ convertFrom })))!
    const position = {
      coords: { longitude: 104.097, latitude: 30.678, accuracy: 30 },
      timestamp: Date.now(),
    } as GeolocationPosition
    function acquired() {
      getCurrentPosition.mock.calls.at(-1)![0](position)
    }
    function converted() {
      convertFrom.mock.calls[0]![2]('complete', {
        locations: [{ getLng: () => 104.1, getLat: () => 30.675 }],
      })
    }
    return { state, getCurrentPosition, convertFrom, acquired, converted, scope }
  }
  afterEach(() => {
    scopes.splice(0).forEach((scope) => scope.stop())
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })
  it('初始不取位置，点击只请求一次；转换后使用GCJ02并保留精度和时间', () => {
    const { state, getCurrentPosition, convertFrom, acquired, converted } = setup()
    expect(getCurrentPosition).not.toHaveBeenCalled()
    state.locate()
    state.locate()
    expect(getCurrentPosition).toHaveBeenCalledTimes(1)
    expect(getCurrentPosition.mock.calls[0]![2]).toEqual({
      enableHighAccuracy: true,
      timeout: 8000,
      maximumAge: 0,
    })
    acquired()
    expect(convertFrom.mock.calls[0]!.slice(0, 2)).toEqual([[104.097, 30.678], 'gps'])
    expect(state.location.value).toBeNull()
    converted()
    expect(state.location.value).toEqual({
      center: [104.1, 30.675],
      accuracy: 30,
      timestamp: Date.now(),
    })
    expect(state.locating.value).toBe(false)
    expect(vi.getTimerCount()).toBe(0)
    // 再次点击重新获取，不继续显示旧位置为当前位置。
    state.locate()
    expect(state.location.value).toBeNull()
    expect(getCurrentPosition).toHaveBeenCalledTimes(2)
  })
  it.each([1, 2, 3])('定位失败%s时不调用坐标服务、不造出位置', (code) => {
    const { state, getCurrentPosition, convertFrom } = setup()
    state.locate()
    getCurrentPosition.mock.calls[0]![1]!({ code } as GeolocationPositionError)
    expect(state.location.value).toBeNull()
    expect(state.locating.value).toBe(false)
    expect(state.message.value).toMatch(/权限|无法|超时/)
    expect(convertFrom).not.toHaveBeenCalled()
  })
  it('转换失败可重试；超时后不接受旧回调覆盖新请求', async () => {
    const { state, convertFrom, acquired, converted } = setup()
    state.locate()
    acquired()
    convertFrom.mock.calls[0]![2]('error', {})
    expect(state.message.value).toContain('转换失败')
    state.locate()
    acquired()
    await vi.advanceTimersByTimeAsync(20000)
    expect(state.message.value).toContain('超时')
    state.locate()
    converted()
    expect(state.location.value).toBeNull()
    expect(state.locating.value).toBe(true)
  })
  it('非安全环境和不支持定位时直接提示，不弹权限', () => {
    const { state, getCurrentPosition } = setup()
    vi.stubGlobal('isSecureContext', false)
    state.locate()
    expect(state.message.value).toContain('HTTPS')
    vi.stubGlobal('isSecureContext', true)
    vi.stubGlobal('navigator', {})
    state.locate()
    expect(state.message.value).toContain('不支持')
    expect(getCurrentPosition).not.toHaveBeenCalled()
  })
  it('等待授权超过20秒后，仍接受位置并完成转换', async () => {
    const { state, acquired, converted } = setup()
    state.locate()
    await vi.advanceTimersByTimeAsync(30000)
    expect(state.locating.value).toBe(true)
    acquired()
    converted()
    expect(state.location.value?.center).toEqual([104.1, 30.675])
    expect(state.locating.value).toBe(false)
  })
  it('卸载取消计时并忽略迟到定位和转换结果', () => {
    const { state, scope, acquired, converted } = setup()
    state.locate()
    acquired()
    scope.stop()
    converted()
    expect(state.location.value).toBeNull()
    expect(vi.getTimerCount()).toBe(0)
  })
})
