import { captureLinkTarget } from '@/shared/test/captureLinkTarget'
import { captureResourceTarget } from '@/shared/test/captureResourceTarget'
/* eslint-disable vue/one-component-per-file -- 独立桩组件用于验证详情刷新时的挂载稳定性。 */
import { createPinia } from 'pinia'
import { AxiosError, AxiosHeaders } from 'axios'
import { createApp, h, nextTick, ref } from 'vue'
import type { App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchRestaurant } from '../api/restaurantApi'
import { restaurantFixture } from '../api/restaurant.test-data'
import RestaurantDetail from './RestaurantDetail.vue'
import { fetchRestaurantAlbum } from '../api/restaurantAlbumApi'

vi.mock('../api/restaurantApi', () => ({ fetchRestaurant: vi.fn() }))
vi.mock('../api/restaurantAlbumApi', () => ({
  fetchRestaurantAlbum: vi.fn(),
  fetchRestaurantAlbumImage: vi.fn(),
}))
// 返回入口只依赖路由来源；管理操作的离开确认由其独立路由测试覆盖。
vi.mock('./RestaurantManagementActions.vue', () => ({ default: { render: () => null } }))
// 只模拟发布确认事件，验证详情统计生命周期；真实发布/回复由各自的集成测试覆盖。
vi.mock('@/modules/review', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    ReviewComposer: defineComponent({
      emits: ['published'],
      setup(_, { emit }) {
        return () =>
          h('button', { class: 'test-publish', onClick: () => emit('published') }, '确认发布')
      },
    }),
    ReviewPanel: defineComponent({
      props: {
        restaurantId: { type: String, default: '' },
        revision: { type: Number, default: 0 },
      },
      setup(props) {
        return () =>
          h(
            'div',
            { class: 'test-review-panel', 'data-revision': props.revision },
            props.restaurantId,
          )
      },
    }),
  }
})

/** 直达链接独立请求；只有404展示缺失页，故障可重试，移除冗余提示及复制分享入口。 */
describe('restaurant detail API integration', () => {
  let app: App | undefined
  let host: HTMLDivElement
  const currentId = ref(restaurantFixture.id)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: ['/', '/map', '/restaurants/:id', '/:pathMatch(.*)*'].map((path) => ({
      path,
      component: { template: '<div />' },
    })),
  })
  const settle = async () => {
    for (let i = 0; i < 6; i++) await nextTick()
  }
  beforeEach(async () => {
    vi.resetAllMocks()
    vi.mocked(fetchRestaurant).mockResolvedValue(restaurantFixture)
    vi.mocked(fetchRestaurantAlbum).mockResolvedValue({
      records: [],
      nextCursor: null,
    })
    currentId.value = restaurantFixture.id
    await router.push('/')
    host = document.createElement('div')
    document.body.append(host)
  })
  afterEach(() => {
    app?.unmount()
    host.remove()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })
  async function mount() {
    app = createApp({
      render: () => h(RestaurantDetail, { id: currentId.value }),
    })
      .use(router)
      .use(createPinia())
    app.mount(host)
    await settle()
  }

  it('loads the direct ID and preserves unknown values without internal labels', async () => {
    await mount()
    expect(fetchRestaurant).toHaveBeenCalledWith(restaurantFixture.id, expect.any(AbortSignal))
    expect(host.querySelector('h1')?.textContent).toBe(restaurantFixture.name)
    expect(fetchRestaurantAlbum).toHaveBeenCalledWith(
      restaurantFixture.id,
      null,
      expect.any(AbortSignal),
    )
    expect(host.querySelector<HTMLElement>('.restaurant-album')?.style.display).toBe('none')
    expect(host.querySelector('.detail-hero')?.classList.contains('has-album')).toBe(false)
    expect(host.querySelector('.detail-preview')).toBeNull()
    expect(host.querySelector('.detail-extra details')).toBeNull()
    expect(host.querySelector('.detail-tags')?.textContent ?? '').not.toContain('联调模拟')
    expect(host.querySelector('.detail-cup')).toBeNull()
    expect(host.querySelector('.hero-score')?.textContent).toContain('待评级')
    expect(host.querySelector('.score-summary')?.textContent).toContain('待评级')
    expect(host.querySelector('.hero-price')).toBeNull()
    expect(host.querySelector('.distance-note')).toBeNull()
  })

  it('四维评分默认折叠，展开和收起保留真实数值，切店后重新折叠', async () => {
    vi.mocked(fetchRestaurant).mockResolvedValueOnce({
      ...restaurantFixture,
      score: 7,
      price: 20,
      priceKind: 'estimate',
      dimensions: { taste: 8, value: 7, service: null, ambience: 6 },
    })
    await mount()
    expect(host.querySelector('.hero-score')?.textContent).toBe('3.5')
    expect(host.querySelector('.detail-subtitle .star-symbols')).toBeNull()
    expect(host.querySelector('.detail-service-tags .hero-price')?.textContent).toContain('¥20')
    const toggle = host.querySelector<HTMLButtonElement>('.score-toggle')!
    const dimensions = host.querySelector<HTMLElement>('.score-summary')!
    expect(toggle.getAttribute('aria-controls')).toBe(dimensions.id)
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    expect(dimensions.style.display).toBe('none')
    toggle.click()
    await settle()
    expect(toggle.getAttribute('aria-expanded')).toBe('true')
    expect(dimensions.style.display).not.toBe('none')
    expect([...dimensions.querySelectorAll('strong')].map((node) => node.textContent)).toEqual([
      '4.0',
      '3.5',
      '待评级',
      '3.0',
    ])
    toggle.click()
    await settle()
    expect(dimensions.style.display).toBe('none')
    toggle.click()
    await settle()
    currentId.value = '2'
    await settle()
    expect(host.querySelector('.score-toggle')?.getAttribute('aria-expanded')).toBe('false')
    expect(host.querySelector<HTMLElement>('.score-summary')?.style.display).toBe('none')
  })

  it('distinguishes a missing record from a temporary server failure', async () => {
    const headers = new AxiosHeaders()
    vi.mocked(fetchRestaurant).mockRejectedValueOnce(
      new AxiosError('missing', '', undefined, undefined, {
        status: 404,
        statusText: '',
        data: {},
        headers,
        config: { headers },
      }),
    )
    await mount()
    expect(host.querySelector('h1')?.textContent).toBe('没有找到这家店。')
    vi.mocked(fetchRestaurant).mockRejectedValueOnce(new Error('服务暂不可用'))
    currentId.value = '2'
    await settle()
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('服务暂不可用')
    host.querySelector<HTMLButtonElement>('[role="alert"] button')!.click()
    await settle()
    expect(host.querySelector('h1')?.textContent).toBe(restaurantFixture.name)
  })

  it('keeps navigation without copy or share controls', async () => {
    vi.mocked(fetchRestaurant).mockResolvedValueOnce({
      ...restaurantFixture,
      latitude: 30.7,
      longitude: 104.0,
      needsLocation: false,
    })
    await mount()
    expect(host.querySelector('.copy-address')).toBeNull()
    expect(host.querySelector('.share-button')).toBeNull()
    expect(host.querySelector('.manual-copy')).toBeNull()
    const navigation = host.querySelector<HTMLAnchorElement>('.detail-navigation')!
    expect(navigation.getAttribute('aria-label')).toBe('在高德中打开导航')
    expect(captureResourceTarget(navigation)).toContain('uri.amap.com')
    expect(host.querySelector('.detail-extra .location-links')).toBeNull()
  })
  it('地图入口返回地图，普通入口使用统一返回文案', async () => {
    await router.push({ path: `/restaurants/${restaurantFixture.id}`, query: { from: 'map' } })
    await mount()
    const link = host.querySelector<HTMLAnchorElement>('.detail-breadcrumb a')!
    expect(link.getAttribute('aria-label')).toBe('返回')
    expect(await captureLinkTarget(app, link)).toBe('/map')
    await new Promise<void>((resolve) => {
      const stop = router.afterEach(() => {
        stop()
        resolve()
      })
      link.click()
    })
    await settle()
    expect(router.currentRoute.value.path).toBe('/map')
    await router.push(`/restaurants/${restaurantFixture.id}`)
    await settle()
    expect(host.querySelector('.detail-breadcrumb a')?.getAttribute('aria-label')).toBe('返回')
  })
  it('选店来源直达时返回选店入口', async () => {
    await router.push({ path: `/restaurants/${restaurantFixture.id}`, query: { from: 'picker' } })
    await mount()
    const link = host.querySelector<HTMLAnchorElement>('.detail-breadcrumb a')!
    expect(link.getAttribute('aria-label')).toBe('返回')
    expect(await captureLinkTarget(app, link)).toBe('/?picker=return')
    await new Promise<void>((resolve) => {
      const stop = router.afterEach(() => {
        stop()
        resolve()
      })
      link.click()
    })
    expect(router.currentRoute.value.fullPath).toBe('/?picker=return')
  })
  it('地图来源的临时故障仍提供返回地图入口', async () => {
    await router.push({ path: `/restaurants/${restaurantFixture.id}`, query: { from: 'map' } })
    vi.mocked(fetchRestaurant).mockRejectedValueOnce(new Error('offline'))
    await mount()
    const link = host.querySelector<HTMLAnchorElement>('.missing-restaurant a')!
    expect(link.textContent).toBe('返回')
    expect(await captureLinkTarget(app, link)).toBe('/map')
  })

  it('ignores the previous restaurant response after switching routes', async () => {
    let resolveOld!: (value: typeof restaurantFixture) => void
    vi.mocked(fetchRestaurant).mockReturnValueOnce(
      new Promise((resolve) => {
        resolveOld = resolve
      }),
    )
    await mount()
    expect(host.querySelector('[role="status"]')?.textContent).toContain('正在加载')
    currentId.value = '2'
    await settle()
    resolveOld({ ...restaurantFixture, name: '过期响应' })
    await settle()
    expect(host.querySelector('h1')?.textContent).toBe(restaurantFixture.name)
  })

  it('preserves review counts and a real zero without small-sample hints', async () => {
    vi.mocked(fetchRestaurant).mockResolvedValueOnce({
      ...restaurantFixture,
      score: 0,
      participantCount: 2,
      reviewCount: 7,
    })
    await mount()
    expect(host.querySelector('.hero-score')?.textContent).toContain('0.0')
    expect(host.querySelector('.detail-subtitle')?.textContent).toContain('7 条点评')
    expect(host.textContent).not.toContain('样本较少')
    expect(host.querySelector('.score-details')).toBeNull()
  })

  it('refreshes statistics after acknowledgement without unmounting reviews and retries reading separately', async () => {
    await mount()
    const panel = host.querySelector('.test-review-panel')!
    vi.mocked(fetchRestaurant).mockRejectedValueOnce(new Error('刷新断网'))
    host.querySelector<HTMLButtonElement>('.test-publish')!.click()
    await settle()
    expect(host.querySelector('.test-review-panel')).toBe(panel)
    expect(panel.getAttribute('data-revision')).toBe('1')
    expect(host.querySelector('.panel-note[role="alert"]')?.textContent).toContain('评分刷新失败')
    vi.mocked(fetchRestaurant).mockResolvedValueOnce({
      ...restaurantFixture,
      score: 7.5,
      participantCount: 2,
      reviewCount: 3,
    })
    host.querySelector<HTMLButtonElement>('.panel-note[role="alert"] button')!.click()
    await settle()
    expect(host.querySelector('.hero-score')?.textContent).toContain('3.8')
    expect(host.querySelector('.panel-note[role="alert"]')).toBeNull()
    expect(host.querySelector('.test-review-panel')).toBe(panel)
    expect(panel.getAttribute('data-revision')).toBe('1')
  })

  it('ignores old statistics if the route changes while refreshing', async () => {
    await mount()
    let old!: (value: typeof restaurantFixture) => void
    vi.mocked(fetchRestaurant).mockReturnValueOnce(
      new Promise((resolve) => {
        old = resolve
      }),
    )
    host.querySelector<HTMLButtonElement>('.test-publish')!.click()
    await settle()
    vi.mocked(fetchRestaurant).mockResolvedValueOnce({
      ...restaurantFixture,
      id: '2',
      name: '新餐厅',
      score: 6,
    })
    currentId.value = '2'
    await settle()
    old({ ...restaurantFixture, name: '旧统计覆盖', score: 10 })
    await settle()
    expect(host.querySelector('h1')?.textContent).toBe('新餐厅')
    expect(host.querySelector('.hero-score')?.textContent).toContain('3.0')
  })

  it('shows canteen reviews without floor or window controls', async () => {
    vi.mocked(fetchRestaurant).mockResolvedValueOnce({
      ...restaurantFixture,
      category: '食堂',
      restaurantType: 'CANTEEN',
    })
    await mount()
    expect(host.querySelector('.detail-affiliation')?.textContent).toBe('校园食堂')
    expect(host.querySelector('.canteen-windows')).toBeNull()
    expect(host.querySelector('.hero-score')?.textContent).toContain('待评级')
    expect(host.querySelector('.test-review-panel')).not.toBeNull()
    expect(host.querySelectorAll('[aria-label="按楼层筛选窗口"] button')).toHaveLength(0)
    expect(host.textContent).not.toContain('历史食堂点评')
    expect(host.querySelector('.legacy-canteen-reviews')).toBeNull()
  })

  it('uses direct canteen review statistics from the backend', async () => {
    vi.mocked(fetchRestaurant).mockResolvedValueOnce({
      ...restaurantFixture,
      id: '100',
      category: '食堂',
      restaurantType: 'CANTEEN',
      score: 7,
    })
    await mount()
    expect(host.querySelector('.hero-score strong')?.textContent).toBe('3.5')
    expect(host.querySelector('.aggregate-source')).toBeNull()
    expect(host.textContent).not.toContain('汇总自')
    expect(host.querySelectorAll('.window-row')).toHaveLength(0)
    expect(host.querySelector('.restaurant-album')).not.toBeNull()
    expect(host.querySelector('.score-summary')).not.toBeNull()
  })
})
