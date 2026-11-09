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
      props: { restaurantId: String, revision: Number },
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
    routes: ['/'].map((path) => ({ path, component: { template: '<div />' } })),
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
    expect(host.querySelector('.restaurant-album')?.textContent).toContain('暂无商户照片')
    expect(host.querySelector('.detail-preview')).toBeNull()
    expect(host.querySelector('.detail-extra details')).toBeNull()
    expect(host.querySelector('.detail-tags')?.textContent ?? '').not.toContain('联调模拟')
    expect(host.querySelector('.detail-cup')).toBeNull()
    expect(host.querySelector('.hero-score')?.textContent).toContain('待评级')
    expect(host.querySelector('.score-summary')?.textContent).toContain('待评级')
    expect(host.querySelector('.detail-subtitle')?.textContent).toContain('人均待补')
    expect(host.querySelector('.distance-note')).toBeNull()
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
    expect(host.querySelector('.location-links a')?.textContent).toContain('在高德中打开')
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
      recommendRate: 0,
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

  it('shows canteen windows without legacy overall reviews or redundant floor controls', async () => {
    vi.mocked(fetchRestaurant).mockResolvedValueOnce({
      ...restaurantFixture,
      category: '食堂',
    })
    await mount()
    expect(host.querySelector('.detail-affiliation')?.textContent).toBe('校园食堂')
    expect(host.querySelector('.canteen-windows')?.textContent).toContain('窗口资料尚未接入')
    expect(host.querySelector('.hero-score')?.textContent).toContain('待评级')
    expect(host.querySelector('.test-review-panel')).toBeNull()
    expect(host.querySelectorAll('[aria-label="按楼层筛选窗口"] button')).toHaveLength(0)
    expect(host.textContent).not.toContain('历史食堂点评')
    expect(host.querySelector('.legacy-canteen-reviews')).toBeNull()
  })

  it('keeps window reviews, publication and statistics on its own ID and only uses the parent for navigation', async () => {
    const window = {
      ...restaurantFixture,
      id: '201',
      name: '测试窗口详情',
      score: 7.5,
      participantCount: 1,
      dining: {
        kind: 'window' as const,
        campus: '测试校区',
        parent: { id: '100', name: '测试食堂' },
        floor: '二楼',
        description: null,
        windows: null,
        dishes: [{ id: 'd1', name: '测试菜品', price: 0 }],
      },
    }
    currentId.value = '201'
    vi.mocked(fetchRestaurant).mockResolvedValue(window)
    await mount()
    expect(host.querySelector('.detail-breadcrumb a')?.getAttribute('href')).toContain(
      '/restaurants/100?floor=',
    )
    expect(host.querySelector('.detail-affiliation')?.textContent).toContain('二楼')
    expect(host.querySelector('.test-review-panel')?.textContent).toBe('201')
    expect(host.querySelector('.detail-dishes')?.textContent).toContain('¥0')
    expect(host.querySelector('.hero-score')?.textContent).toContain('3.8')
    expect(host.textContent).not.toContain('样本较少')
    host.querySelector<HTMLButtonElement>('.test-publish')!.click()
    await settle()
    expect(vi.mocked(fetchRestaurant).mock.calls.every(([id]) => id === '201')).toBe(true)
    expect(host.querySelector('.test-review-panel')?.getAttribute('data-revision')).toBe('1')
  })

  it('uses the authoritative backend canteen aggregate', async () => {
    vi.mocked(fetchRestaurant).mockResolvedValueOnce({
      ...restaurantFixture,
      id: '100',
      category: '食堂',
      score: 7,
      dining: {
        kind: 'canteen',
        campus: null,
        parent: null,
        floor: null,
        description: null,
        dishes: [],
        windows: [
          {
            id: '201',
            canteenId: '100',
            name: '窗口A',
            floor: '一楼',

            score: 8,
            reviewCount: 100,
            price: null,
            closed: false,
            specialties: [],
          },
          {
            id: '202',
            canteenId: '100',
            name: '窗口B',
            floor: '二楼',

            score: 6,
            reviewCount: 1,
            price: null,
            closed: false,
            specialties: [],
          },
          {
            id: '203',
            canteenId: '100',
            name: '暂停窗口',
            floor: '二楼',

            score: 10,
            reviewCount: 9,
            price: null,
            closed: true,
            specialties: [],
          },
        ],
      },
    })
    await mount()
    expect(host.querySelector('.hero-score strong')?.textContent).toBe('3.5')
    expect(host.querySelector('.aggregate-source')).toBeNull()
    expect(host.textContent).not.toContain('汇总自')
    expect(host.querySelectorAll('.window-row')).toHaveLength(3)
    expect(host.querySelector('.restaurant-album')).not.toBeNull()
    expect(host.querySelector('.score-summary')).toBeNull()
  })
})
