import { createApp, h, nextTick } from 'vue'
import type { App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RestaurantRankings from './RestaurantRankings.vue'
import type { RankingLoader, RestaurantRanking } from '../api/restaurantRankingApi'

const fixture = (): RestaurantRanking => ({
  campusCode: 'QINGSHUIHE',
  periodType: 'WEEK',
  status: 'READY',
  scoreScale: 5,
  windowStartAt: '2026-11-04T04:00:00Z',
  windowEndAt: '2026-11-11T04:00:00Z',
  generatedAt: '2026-11-11T04:00:00Z',
  items: [
    {
      rank: 1,
      restaurantId: '9007199254740993',
      name: '第一名',
      coverUrl: '/api/restaurants/9007199254740993/photos/81',
      tasteScore: 4.7,
      recommendationScore: 4.2,
      participantCount: 46,
    },
    {
      rank: 2,
      restaurantId: '12',
      name: '第二名',
      coverUrl: null,
      tasteScore: 4.8,
      recommendationScore: 4.2,
      participantCount: 9,
    },
  ],
})

describe('近期榜单页面', () => {
  let app: App | undefined
  let host: HTMLDivElement | undefined
  afterEach(() => {
    app?.unmount()
    app = undefined
    host?.remove()
  })

  async function mount(loader: RankingLoader, url = '/restaurant-rankings') {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/restaurant-rankings', component: RestaurantRankings },
        { path: '/', component: { template: '<div />' } },
        { path: '/restaurants/:id', component: { template: '<div />' } },
      ],
    })
    await router.push(url)
    host = document.createElement('div')
    document.body.append(host)
    app = createApp({ render: () => h(RestaurantRankings, { loader }) }).use(router)
    app.mount(host)
    await nextTick()
    return router
  }

  it('沿用服务端名次、五分制及字符串ID，失败封面变为占位', async () => {
    await mount(vi.fn().mockResolvedValue(fixture()))
    await vi.waitFor(() => expect(host!.querySelectorAll('.ranking-row')).toHaveLength(2))
    expect([...host!.querySelectorAll('.ranking-name')].map((el) => el.textContent)).toEqual([
      '第一名',
      '第二名',
    ])
    expect(host!.querySelector('.ranking-score strong')?.textContent).toBe('4.2')
    expect(host!.querySelector('.ranking-row')?.getAttribute('href')).toBe(
      '/restaurants/9007199254740993',
    )
    expect(host!.querySelector('.ranking-count')?.textContent).toContain('46人评价')
    expect(host!.querySelector('time')?.getAttribute('datetime')).toBe('2026-11-11T04:00:00Z')
    host!.querySelector('img')!.dispatchEvent(new Event('error'))
    await nextTick()
    expect(host!.querySelectorAll('img')).toHaveLength(0)
    expect(host!.textContent).toContain('图片暂不可用')
    expect(host!.textContent).not.toContain('样本')
  })

  it('URL直达恢复校区周期，切换更新URL且浏览器返回恢复选择', async () => {
    const loader = vi.fn<RankingLoader>().mockImplementation(async (campusCode, periodType) => ({
      ...fixture(),
      campusCode,
      periodType,
    }))
    const router = await mount(loader, '/restaurant-rankings?campusCode=SHAHE&periodType=MONTH')
    await vi.waitFor(() =>
      expect(host!.querySelector('.ranking-count')?.textContent).toContain('近30天'),
    )
    expect(loader).toHaveBeenLastCalledWith('SHAHE', 'MONTH', expect.any(AbortSignal))
    const campusButton = host!.querySelector<HTMLButtonElement>('.ranking-campuses button')!
    campusButton.click()
    await vi.waitFor(() => expect(router.currentRoute.value.query.campusCode).toBe('QINGSHUIHE'))
    expect(loader).toHaveBeenLastCalledWith('QINGSHUIHE', 'MONTH', expect.any(AbortSignal))
    router.back()
    await vi.waitFor(() => expect(router.currentRoute.value.query.campusCode).toBe('SHAHE'))
  })

  it('快速切换取消旧请求，即使旧响应迟到也不能覆盖新榜', async () => {
    let resolveOld!: (value: RestaurantRanking) => void
    const signals: AbortSignal[] = []
    const loader: RankingLoader = (campusCode, periodType, signal) => {
      signals.push(signal)
      if (signals.length === 1)
        return new Promise((resolve) => {
          resolveOld = resolve
        })
      return Promise.resolve({
        ...fixture(),
        campusCode,
        periodType,
        items: [{ ...fixture().items[0]!, name: '新校区餐厅' }],
      })
    }
    const router = await mount(loader)
    await router.push('/restaurant-rankings?campusCode=SHAHE')
    await vi.waitFor(() => expect(host!.textContent).toContain('新校区餐厅'))
    expect(signals[0]?.aborted).toBe(true)
    resolveOld(fixture())
    await nextTick()
    expect(host!.textContent).toContain('新校区餐厅')
    expect(host!.textContent).not.toContain('第一名')
    app!.unmount()
    app = undefined
    expect(signals[1]?.aborted).toBe(true)
  })

  it.each([
    ['READY', '还没有餐厅上榜'],
    ['PREPARING', '榜单准备中'],
    ['UNAVAILABLE', '榜单暂时不可用'],
  ] as const)('单独展示%s，不展示过期条目或将其混为请求错误', async (status, title) => {
    const data = { ...fixture(), status, items: [] }
    await mount(vi.fn().mockResolvedValue(data))
    await vi.waitFor(() => expect(host!.textContent).toContain(title))
    expect(host!.querySelectorAll('.ranking-row')).toHaveLength(0)
    expect(host!.querySelector('[role="alert"]')).toBeNull()
    if (status === 'UNAVAILABLE') expect(host!.querySelector('time')).toBeNull()
  })

  it('请求失败保留重试入口，成功后正常显示榜单', async () => {
    const loader = vi
      .fn<RankingLoader>()
      .mockRejectedValueOnce(new Error('isolated failure'))
      .mockResolvedValue(fixture())
    await mount(loader)
    await vi.waitFor(() => expect(host!.textContent).toContain('榜单暂时无法加载'))
    host!.querySelector<HTMLButtonElement>('.ranking-state button')!.click()
    await vi.waitFor(() => expect(host!.querySelectorAll('.ranking-row')).toHaveLength(2))
    expect(host!.querySelector('[role="alert"]')).toBeNull()
  })
})
