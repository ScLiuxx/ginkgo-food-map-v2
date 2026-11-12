import { createApp, h, nextTick, reactive, type App } from 'vue'
import { createPinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AMapCanvas from './AMapCanvas.vue'
import { loadAMap } from '../sdk/amap'
import type { AMapSDK } from '../sdk/types'
import type { MapBounds, MapFocus, MapPoint } from '../api/mapApi'

vi.mock('../sdk/amap', () => ({ loadAMap: vi.fn() }))

/** 以可控SDK事件区分点选定位和用户浏览，防止程序放大后把原餐厅结果缩成门店周边。 */
describe('地图程序定位与候选范围隔离', () => {
  let app: App
  let host: HTMLDivElement
  let bounds: MapBounds
  let props: { points: MapPoint[]; selected: string | null; focus: MapFocus | null }
  let resize: () => void
  const events = new Map<string, () => void>()
  const moves: MapBounds[] = []
  const area = { west: 103.9, east: 104.2, south: 30.6, north: 30.8 }
  beforeEach(async () => {
    events.clear()
    moves.length = 0
    bounds = { ...area }
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(callback: () => void) {
          resize = callback
        }
        observe() {}
        disconnect() {}
      },
    )
    const map = {
      getBounds: () => ({
        getSouthWest: () => ({ getLng: () => bounds.west, getLat: () => bounds.south }),
        getNorthEast: () => ({ getLng: () => bounds.east, getLat: () => bounds.north }),
      }),
      on: (name: string, callback: () => void) => events.set(name, callback),
      off: vi.fn(),
      add: vi.fn(),
      remove: vi.fn(),
      destroy: vi.fn(),
      setZoomAndCenter: vi.fn(() => events.get('moveend')?.()),
    }
    vi.mocked(loadAMap).mockResolvedValue({
      Map: function () {
        return map
      },
    } as unknown as AMapSDK)
    props = reactive({ points: [], selected: null, focus: null })
    host = document.createElement('div')
    document.body.append(host)
    app = createApp({
      setup: () => () =>
        h(AMapCanvas, { ...props, onMove: (value: MapBounds) => moves.push(value) }),
    }).use(createPinia())
    app.mount(host)
    for (let i = 0; i < 8; i++) await nextTick()
    events.get('complete')!()
    expect(moves).toEqual([area])
  })
  afterEach(() => {
    app.unmount()
    host.remove()
    vi.unstubAllGlobals()
    vi.resetAllMocks()
  })
  async function focus(preserveResults = true) {
    bounds = { west: 104.01, east: 104.02, south: 30.7, north: 30.71 }
    props.focus = { longitude: 104.015, latitude: 30.705, sequence: 1, preserveResults }
    await nextTick()
  }
  it('点选自动放大及其后续moveend/resize均不重写原候选范围', async () => {
    await focus()
    events.get('zoomend')!()
    events.get('moveend')!()
    resize()
    expect(moves).toEqual([area])
  })
  it.each(['pointerdown', 'wheel'])('真实%s后恢复查询新的地图范围', async (type) => {
    await focus()
    host.querySelector('.amap-canvas')!.dispatchEvent(new Event(type, { bubbles: true }))
    events.get('moveend')!()
    expect(moves).toEqual([area, bounds])
  })
  it('Tab切换焦点不恢复查询，方向键浏览恢复查询', async () => {
    await focus()
    host
      .querySelector('.amap-canvas')!
      .dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true }))
    resize()
    expect(moves).toHaveLength(1)
    host
      .querySelector('.amap-canvas')!
      .dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true }))
    events.get('moveend')!()
    expect(moves).toHaveLength(2)
  })
  it('未要求保留结果的程序定位仍按既有事件更新范围', async () => {
    await focus(false)
    expect(moves).toEqual([area, bounds])
  })
})
