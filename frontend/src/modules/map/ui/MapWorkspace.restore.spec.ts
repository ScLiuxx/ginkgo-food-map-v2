/* eslint-disable vue/one-component-per-file -- 路由测试同时需要应用壳和SDK替身。 */
import { createApp, h, nextTick, type App, type PropType } from 'vue'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView, type Router } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MapWorkspace from './MapWorkspace.vue'
import {
  fetchMapPoints,
  type MapPoints,
  type MapCamera,
  type MapFocus,
  type MapPoint,
} from '../api/mapApi'
import { useMapBrowsing } from '../model/browsing.store'
import { useRestaurantCatalog } from '@/modules/restaurant/model/catalog.store'
import { mapExamples } from '../model/map'

const fixture = vi.hoisted(() => ({
  bounds: { west: 104, east: 104.2, south: 30.6, north: 30.8 },
  camera: { center: [104.1, 30.7] as [number, number], zoom: 13 },
}))
vi.mock('../api/mapApi', () => ({ fetchMapPoints: vi.fn() }))
vi.mock('@/modules/restaurant/api/restaurantApi', () => ({
  fetchRestaurants: vi.fn(),
  fetchRestaurantCategories: vi.fn(async () => []),
}))
vi.mock('./AMapCanvas.vue', async () => {
  const { defineComponent, h, onMounted } = await import('vue')
  return {
    default: defineComponent({
      props: {
        initialCamera: { type: Object as PropType<MapCamera | null>, default: null },
        points: { type: Array as PropType<MapPoint[]>, default: () => [] },
        selected: { type: String, default: null },
        focus: { type: Object as PropType<MapFocus | null>, default: null },
      },
      emits: ['move', 'view', 'hover'],
      setup(props, { emit, expose }) {
        expose({ captureCamera: () => fixture.camera })
        onMounted(() => {
          if (!props.initialCamera) emit('move', fixture.bounds)
          emit('view', props.initialCamera ?? fixture.camera)
        })
        return () =>
          h(
            'div',
            {
              class: 'fake-map',
              'data-camera': JSON.stringify(props.initialCamera),
            },
            h('button', { class: 'fake-hover', onClick: () => emit('hover', '1') }, '悬停'),
          )
      },
    }),
  }
})

/** 真实路由卸载/重建工作区，验证相机、候选范围和滚动分别恢复，而非只缓存输入框。 */
describe('地图浏览现场恢复', () => {
  let app: App, host: HTMLDivElement, router: Router
  let pinia: ReturnType<typeof createPinia>
  const batch = (count = 10): MapPoints => {
    const records = Array.from({ length: count }, (_, index) => ({
      ...mapExamples[0]!,
      id: String(index + 1),
      name: `恢复店铺${index + 1}`,
      latitude: 30.7,
      longitude: 104.1,
      needsLocation: false,
    }))
    return {
      records,
      points: records.map((row) => ({
        id: row.id,
        name: row.name,
        longitude: row.longitude,
        latitude: row.latitude,
        score: row.score,
        pricePerPerson: row.price,
        businessStatus: 'OPERATING',
      })),
      matchedCount: 20,
      returnedCount: count,
      limit: 100,
      truncated: true,
    }
  }
  async function flush() {
    for (let i = 0; i < 12; i++) await nextTick()
  }
  beforeEach(async () => {
    vi.useFakeTimers()
    vi.mocked(fetchMapPoints).mockResolvedValue(batch())
    fixture.camera = { center: [104.1, 30.7], zoom: 13 }
    pinia = createPinia()
    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/map', component: { render: () => h(MapWorkspace, { key: 'live' }) } },
        {
          path: '/previews/map',
          component: { render: () => h(MapWorkspace, { key: 'preview', preview: true }) },
        },
        { path: '/detail', component: { render: () => h('p', '详情') } },
        { path: '/:pathMatch(.*)*', component: { render: () => h('p', '其他页面') } },
      ],
    })
    await router.push('/map')
    host = document.createElement('div')
    document.body.append(host)
    app = createApp({ render: () => h(RouterView) })
      .use(pinia)
      .use(router)
    app.mount(host)
    await flush()
  })
  afterEach(() => {
    app.unmount()
    host.remove()
    vi.useRealTimers()
    vi.resetAllMocks()
  })
  it('离开再返回恢复条件、实际视角、选中店铺和列表位置，刷新仍使用原候选范围', async () => {
    const catalog = useRestaurantCatalog(pinia)
    Object.assign(catalog.filters, {
      keyword: '火锅',
      maxPrice: 30,
      hideClosed: true,
      hasReviews: false,
    })
    await vi.advanceTimersByTimeAsync(300)
    await flush()
    host.querySelectorAll<HTMLButtonElement>('.map-card-list .map-restaurant')[5]!.click()
    await flush()
    fixture.camera = { center: [104.15, 30.73], zoom: 17 }
    const panel = host.querySelector<HTMLElement>('.map-result-panel')!
    panel.scrollTop = 840
    panel.dispatchEvent(new Event('scroll'))
    await router.push('/detail')
    await flush()
    const saved = useMapBrowsing(pinia).snapshot!
    expect(saved.camera).toEqual(fixture.camera)
    expect(saved.viewport.bounds).toEqual(fixture.bounds)
    expect(saved.scrollTop).toBe(840)
    // 模拟离开期间首页改了共享条件；地图快照不能被这些修改污染。
    Object.assign(catalog.filters, {
      keyword: '其他',
      maxPrice: null,
      diningMode: 'delivery',
      hasReviews: true,
    })
    await new Promise<void>((resolve) => {
      const stop = router.afterEach(() => {
        stop()
        resolve()
      })
      router.back()
    })
    await flush()
    expect(host.querySelector<HTMLInputElement>('input[type=search]')!.value).toBe('火锅')
    expect(host.querySelector('.map-selection')!.textContent).toContain('恢复店铺6')
    expect(host.querySelector<HTMLElement>('.map-result-panel')!.scrollTop).toBe(840)
    expect(JSON.parse(host.querySelector('.fake-map')!.getAttribute('data-camera')!)).toEqual(
      fixture.camera,
    )
    expect(fetchMapPoints).toHaveBeenLastCalledWith(
      expect.objectContaining({
        keyword: '火锅',
        maxPrice: 30,
        hideClosed: true,
        diningMode: undefined,
        hasReviews: false,
      }),
      fixture.bounds,
      expect.anything(),
    )
    expect(saved.filters.keyword).toBe('火锅')
    expect(saved.viewport.rows).toHaveLength(10)
  })
  it('返回刷新失败时保留现场；重试后清除已不在集合中的选店，改筛选仍回到顶部', async () => {
    host.querySelectorAll<HTMLButtonElement>('.map-card-list .map-restaurant')[5]!.click()
    await flush()
    const panel = host.querySelector<HTMLElement>('.map-result-panel')!
    panel.scrollTop = 420
    await router.push('/detail')
    vi.mocked(fetchMapPoints).mockRejectedValueOnce(new Error('offline'))
    await router.push('/map')
    await flush()
    expect(host.querySelectorAll('.map-card-list .map-restaurant')).toHaveLength(10)
    expect(host.querySelector('.map-selection')!.textContent).toContain('恢复店铺6')
    expect(host.querySelector<HTMLElement>('.map-result-panel')!.scrollTop).toBe(420)
    vi.mocked(fetchMapPoints).mockImplementation(async () => batch(1))
    host.querySelector<HTMLButtonElement>('.map-auto-status[role=alert] button')!.click()
    await flush()
    expect(host.querySelector('.map-selection')).toBeNull()
    useRestaurantCatalog(pinia).filters.keyword = '新范围'
    await vi.advanceTimersByTimeAsync(300)
    await flush()
    expect(host.querySelector<HTMLElement>('.map-result-panel')!.scrollTop).toBe(0)
  })
  it('不记忆悬停预览，界面预览不覆盖真实现场，新应用会话不继承记录', async () => {
    host.querySelector<HTMLButtonElement>('.fake-hover')!.click()
    await flush()
    expect(host.querySelector('.map-selection')).not.toBeNull()
    await router.push('/previews/map')
    await flush()
    const saved = useMapBrowsing(pinia).snapshot!
    expect(saved.selectedId).toBeNull()
    const calls = vi.mocked(fetchMapPoints).mock.calls.length
    await router.push('/detail')
    await flush()
    expect(useMapBrowsing(pinia).snapshot).toBe(saved)
    expect(fetchMapPoints).toHaveBeenCalledTimes(calls)
    expect(useMapBrowsing(createPinia()).snapshot).toBeNull()
  })
})
