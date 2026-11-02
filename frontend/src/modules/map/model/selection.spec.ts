import { effectScope } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useMapSelection } from './selection'

/** 验证悬停离开、卡片可达、点击固定及销毁清理，避免鼠标预览永久残留。 */
describe('地图卡片悬停与固定', () => {
  const scopes: ReturnType<typeof effectScope>[] = []
  function setup() {
    vi.useFakeTimers()
    const scope = effectScope()
    scopes.push(scope)
    return scope.run(() => useMapSelection())!
  }
  afterEach(() => {
    scopes.splice(0).forEach((s) => s.stop())
    vi.useRealTimers()
  })
  it('离开点位后延迟关闭，进入卡片可取消关闭，离开卡片继续关闭', async () => {
    const state = setup()
    state.hover('1')
    state.leave()
    await vi.advanceTimersByTimeAsync(150)
    expect(state.activeId.value).toBe('1')
    state.keepOpen()
    await vi.advanceTimersByTimeAsync(300)
    expect(state.activeId.value).toBe('1')
    state.leave()
    await vi.advanceTimersByTimeAsync(280)
    expect(state.activeId.value).toBeNull()
  })
  it('点击固定不被鼠标离开关闭；临时预览另一店后恢复固定店', async () => {
    const state = setup()
    state.pin('1')
    state.hover('2')
    state.leave()
    await vi.advanceTimersByTimeAsync(280)
    expect(state.activeId.value).toBe('1')
    state.close()
    expect(state.activeId.value).toBeNull()
  })
  it('快速跨点不会被上一点关闭任务误关', async () => {
    const state = setup()
    state.hover('1')
    state.leave()
    await vi.advanceTimersByTimeAsync(150)
    state.hover('2')
    await vi.advanceTimersByTimeAsync(300)
    expect(state.activeId.value).toBe('2')
  })
  it('卸载时清理关闭任务', () => {
    const state = setup()
    state.hover('1')
    state.leave()
    scopes[0]!.stop()
    expect(vi.getTimerCount()).toBe(0)
  })
})
