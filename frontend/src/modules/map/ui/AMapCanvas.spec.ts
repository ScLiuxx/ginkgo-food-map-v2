/* eslint-disable vue/one-component-per-file -- 测试包含不同初始视角的应用挂载入口。 */
import { createApp, h, nextTick, reactive, ref, type App } from 'vue'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AMapCanvas from './AMapCanvas.vue'
import { loadAMap } from '../sdk/amap'
import type { AMapSDK } from '../sdk/types'
import type { MapBounds, MapCamera, MapFocus, MapPoint } from '../api/mapApi'

vi.mock('../sdk/amap', () => ({ loadAMap: vi.fn() }))

/** 以可控SDK事件区分点选定位和用户浏览，防止程序放大后把原餐厅结果缩成门店周边。 */
describe('地图程序定位与候选范围隔离', () => {
  let app: App
  let host: HTMLDivElement
  let bounds: MapBounds
  let props: { points: MapPoint[]; selected: string | null; focus: MapFocus | null }
  let resize: () => void
  let zoom: number
  let center: [number, number]
  let options: { center: [number, number]; zoom: number }
  let zoomAndCenter: ReturnType<typeof vi.fn>
  let remove: ReturnType<typeof vi.fn>
  let off: ReturnType<typeof vi.fn>
  let destroy: ReturnType<typeof vi.fn>
  const selected: string[] = []
  const hovered: string[] = []
  const events = new Map<string, () => void>()
  const moves: MapBounds[] = []
  const views: MapCamera[] = []
  const canvas = ref<InstanceType<typeof AMapCanvas>>()
  const area = { west: 103.9, east: 104.2, south: 30.6, north: 30.8 }
  beforeEach(async () => {
    events.clear()
    moves.length = 0
    views.length = 0
    selected.length = 0
    hovered.length = 0
    zoom = 11
    center = [104.02, 30.67]
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
    zoomAndCenter = vi.fn((value: number, position: [number, number]) => {
      zoom = value
      center = position
      events.get('zoomend')?.()
      events.get('moveend')?.()
    })
    remove = vi.fn((markers: { getContent(): HTMLElement }[]) => {
      markers.forEach((marker) => marker.getContent().remove())
    })
    off = vi.fn()
    destroy = vi.fn()
    const map = {
      getCenter: () => ({ getLng: () => center[0], getLat: () => center[1] }),
      getZoom: () => zoom,
      lngLatToContainer: ([longitude, latitude]: [number, number]) => ({
        getX: () => longitude * 2 ** zoom,
        getY: () => latitude * 2 ** zoom,
      }),
      getBounds: () => ({
        getSouthWest: () => ({ getLng: () => bounds.west, getLat: () => bounds.south }),
        getNorthEast: () => ({ getLng: () => bounds.east, getLat: () => bounds.north }),
      }),
      on: (name: string, callback: () => void) => events.set(name, callback),
      off,
      add: vi.fn((markers: { getContent(): HTMLElement }[]) => {
        markers.forEach((marker) => host.querySelector('.amap-host')!.append(marker.getContent()))
      }),
      remove,
      destroy,
      setZoomAndCenter: zoomAndCenter,
    }
    vi.mocked(loadAMap).mockResolvedValue({
      Map: function (_host: HTMLElement, initial: typeof options) {
        options = initial
        center = initial.center
        zoom = initial.zoom
        return map
      },
      Marker: function ({ content }: { content: HTMLElement }) {
        return { getContent: () => content }
      },
    } as unknown as AMapSDK)
    props = reactive({ points: [], selected: null, focus: null })
    host = document.createElement('div')
    document.body.append(host)
    app = createApp({
      setup: () => () =>
        h(AMapCanvas, {
          ref: canvas,
          ...props,
          onMove: (value: MapBounds) => moves.push(value),
          onView: (value: MapCamera) => views.push(value),
          onSelect: (id: string) => selected.push(id),
          onHover: (id: string) => hovered.push(id),
        }),
    }).use(createPinia())
    app.use(
      createRouter({
        history: createMemoryHistory(),
        routes: [{ path: '/:pathMatch(.*)*', component: { template: '<p />' } }],
      }),
    )
    app.mount(host)
    for (let i = 0; i < 8; i++) await nextTick()
    events.get('complete')!()
    expect(moves).toEqual([area])
  })
  afterEach(() => {
    app.unmount()
    expect(off).toHaveBeenCalledWith('moveend', expect.any(Function))
    expect(off).toHaveBeenCalledWith('zoomend', expect.any(Function))
    expect(destroy).toHaveBeenCalledOnce()
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
    // SDK缩放和移动各发一次结束事件；实际请求由viewport统一防抖。
    expect(moves).toEqual([area, bounds, bounds])
  })
  it('保存图面使用实际中心和缩放，点选定位锁定期间仍上报视角', async () => {
    await focus()
    const view = { center: [104.015, 30.705], zoom: 16 }
    expect(canvas.value!.captureCamera()).toEqual(view)
    expect(views.at(-1)).toEqual(view)
    expect(moves).toEqual([area])
  })
  it('使用保存视角初始化，启动及resize不覆盖原候选范围，用户操作后恢复查询', async () => {
    app.unmount()
    off.mockClear()
    destroy.mockClear()
    moves.length = 0
    const camera: MapCamera = { center: [104.08, 30.72], zoom: 17 }
    app = createApp(AMapCanvas, {
      points: [],
      selected: null,
      initialCamera: camera,
      onMove: (value: MapBounds) => moves.push(value),
    }).use(createPinia())
    app.use(
      createRouter({
        history: createMemoryHistory(),
        routes: [{ path: '/:pathMatch(.*)*', component: { template: '<p />' } }],
      }),
    )
    app.mount(host)
    for (let i = 0; i < 8; i++) await nextTick()
    expect(options).toEqual(expect.objectContaining(camera))
    events.get('complete')!()
    resize()
    expect(moves).toEqual([])
    host.querySelector('.amap-canvas')!.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    events.get('moveend')!()
    expect(moves).toEqual([bounds])
  })
  const point = (id: string, longitude = 104): MapPoint => ({
    id,
    name: `店铺${id}`,
    longitude,
    latitude: 30.7,
    score: null,
    pricePerPerson: null,
    businessStatus: 'OPERATING',
  })
  it('聚合点击放大并恢复范围查询，缩放后展开单店可点选和悬停', async () => {
    props.points = [point('1'), point('2', 104.01)]
    await nextTick()
    expect(host.querySelectorAll('.amap-score-pin')).toHaveLength(0)
    const cluster = host.querySelector<HTMLButtonElement>('.amap-cluster-pin')!
    expect(cluster.dataset.count).toBe('2')
    await focus()
    // 模拟用户缩回密集视野，再点聚合，不能沿用程序定位的范围锁定。
    zoom = 11
    events.get('zoomend')!()
    host.querySelector<HTMLButtonElement>('.amap-cluster-pin')!.click()
    expect(zoomAndCenter).toHaveBeenLastCalledWith(13, [104.005, 30.7])
    expect(moves.length).toBeGreaterThan(1)
    expect(host.querySelectorAll('.amap-cluster-pin')).toHaveLength(0)
    const single = host.querySelector<HTMLButtonElement>('.amap-score-pin')!
    single.dispatchEvent(new Event('mouseenter'))
    single.click()
    expect(hovered).toEqual(['1'])
    expect(selected).toEqual(['1'])
  })
  it('列表选中密集组内店铺后单独高亮，重新计数没有重复成员', async () => {
    props.points = [point('1'), point('2'), point('3')]
    await nextTick()
    expect(host.querySelector('.amap-cluster-pin')!.textContent).toBe('3')
    props.selected = '2'
    await nextTick()
    expect(host.querySelector('.amap-cluster-pin')!.textContent).toBe('2')
    expect(host.querySelector('.amap-score-pin.active')!.getAttribute('aria-label')).toBe('店铺2')
    props.selected = null
    await nextTick()
    expect(host.querySelectorAll('.amap-score-pin')).toHaveLength(0)
    expect(host.querySelector('.amap-cluster-pin')!.textContent).toBe('3')
  })
  it('单店悬停高亮时保留原按钮和键盘焦点', async () => {
    props.points = [point('1'), point('2', 105)]
    await nextTick()
    const button = host.querySelector<HTMLButtonElement>('.amap-score-pin')!
    button.focus()
    props.selected = '1'
    await nextTick()
    expect(host.querySelector('.amap-score-pin.active')).toBe(button)
    expect(document.activeElement).toBe(button)
    props.selected = null
    await nextTick()
    expect(host.querySelector('.amap-score-pin')).toBe(button)
    expect(button.getAttribute('aria-pressed')).toBe('false')
  })
  it.each([true, false])('同坐标或最大缩放时提供成员名单（同坐标=%s）', async (coincident) => {
    zoom = coincident ? 11 : 20
    props.points = [point('1'), point('2', coincident ? 104 : 104.00001)]
    await nextTick()
    host.querySelector<HTMLButtonElement>('.amap-cluster-pin')!.click()
    await nextTick()
    expect(zoomAndCenter).not.toHaveBeenCalled()
    expect(moves).toEqual([area])
    const members = host.querySelectorAll<HTMLButtonElement>('.cluster-members li button')
    expect(members).toHaveLength(2)
    members[1]!.click()
    await nextTick()
    expect(selected).toEqual(['2'])
    expect(host.querySelector('.cluster-members')).toBeNull()
  })
  it('筛选替换清理旧聚合和名单，空集无残留，卸载移除事件并销毁地图', async () => {
    props.points = [point('1'), point('2')]
    await nextTick()
    host.querySelector<HTMLButtonElement>('.amap-cluster-pin')!.click()
    await nextTick()
    expect(host.querySelector('.cluster-members')).not.toBeNull()
    props.points = [point('3')]
    await nextTick()
    expect(host.querySelector('.cluster-members')).toBeNull()
    expect(host.querySelectorAll('.amap-cluster-pin')).toHaveLength(0)
    expect(host.querySelector('.amap-score-pin')!.getAttribute('aria-label')).toBe('店铺3')
    props.points = []
    await nextTick()
    expect(host.querySelectorAll('.amap-score-pin')).toHaveLength(0)
    expect(remove).toHaveBeenCalled()
  })

  it('放大到最高级仍未分开的聚合立即显示名单，无需再点一次', async () => {
    zoom = 19
    props.points = [point('1'), point('2', 104.000001)]
    await nextTick()
    host.querySelector<HTMLButtonElement>('.amap-cluster-pin')!.click()
    await nextTick()
    expect(zoomAndCenter).toHaveBeenLastCalledWith(20, [104.0000005, 30.7])
    expect(host.querySelectorAll('.cluster-members li')).toHaveLength(2)
  })

  it('迟到的同组刷新保留名单并更新资料，主动关闭后不会因刷新重开', async () => {
    props.points = [point('1'), point('2')]
    await nextTick()
    host.querySelector<HTMLButtonElement>('.amap-cluster-pin')!.click()
    await nextTick()
    props.points = [point('2'), { ...point('1'), name: '更新后的店铺1' }]
    await nextTick()
    expect(host.querySelectorAll('.cluster-members li')).toHaveLength(2)
    expect(host.querySelector('.cluster-members')?.textContent).toContain('更新后的店铺1')
    host.querySelector<HTMLButtonElement>('[aria-label="关闭聚合店铺"]')!.click()
    await nextTick()
    props.points = [point('1'), point('2')]
    await nextTick()
    expect(host.querySelector('.cluster-members')).toBeNull()
  })
})
