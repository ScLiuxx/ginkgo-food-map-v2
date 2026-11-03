import { createApp, nextTick, type App } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import PlaceSearchResults from './PlaceSearchResults.vue'
import type { PlaceResult } from '../model/placeSearch'

/** 验证投稿入口与搜索状态联动，不把第三方地点当作本站详情、图片或评分。 */
describe('外部地点列表和投稿入口', () => {
  let app: App, host: HTMLDivElement
  afterEach(() => {
    app?.unmount()
    host?.remove()
  })
  async function setup(error: string | null = null, canSubmit = false, loading = false) {
    host = document.createElement('div')
    document.body.append(host)
    const select = vi.fn(),
      pageChange = vi.fn(),
      retry = vi.fn()
    const records: PlaceResult[] = [
      {
        id: 'poi-1',
        name: '测试地点',
        address: '测试地址',
        district: '',
        category: '餐饮',
        longitude: 104.1,
        latitude: 30.6,
        match: null,
        matchState: 'unmatched',
      },
    ]
    app = createApp(PlaceSearchResults, {
      records,
      hiddenCount: 0,
      loading,
      canSubmit,
      keyword: '新店关键词',
      error,
      total: 20,
      page: 1,
      pages: 2,
      selected: null,
      onSelect: select,
      onPageChange: pageChange,
      onRetry: retry,
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: ['/', '/restaurants/submit'].map((path) => ({
        path,
        component: { template: '<div />' },
      })),
    })
    await router.push('/')
    app.use(router)
    app.mount(host)
    await nextTick()
    return { select, pageChange, retry, router }
  }
  it('只展示地点基础信息，未授权显示入口时不展示投稿按钮', async () => {
    const { select, pageChange } = await setup()
    host.querySelector<HTMLButtonElement>('.place-row')!.click()
    expect(select).toHaveBeenCalledWith('amap:poi-1')
    expect(host.textContent).toContain('暂未匹配本站餐厅')
    expect(host.querySelector('.map-rating, img, a[href^="/restaurants/"]')).toBeNull()
    expect(host.querySelector('.place-placeholder')).toBeNull()
    host.querySelectorAll<HTMLButtonElement>('.place-pagination button')[1]!.click()
    expect(pageChange).toHaveBeenCalledWith(2)
  })
  it('搜索完成且本站无匹配时可进入同一投稿页并带入关键词', async () => {
    const { router } = await setup(null, true)
    host.querySelector<HTMLButtonElement>('.submit-restaurant-button')!.click()
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/restaurants/submit'))
    expect(router.currentRoute.value.path).toBe('/restaurants/submit')
    expect(router.currentRoute.value.query.keyword).toBe('新店关键词')
  })
  it('搜索加载中不会展示未找到入口', async () => {
    await setup(null, true, true)
    expect(host.querySelector('.place-placeholder')).toBeNull()
  })
  it('服务错误不会伪装成无结果或可提交状态，支持独立重试', async () => {
    const { retry } = await setup('高德搜索暂不可用', true)
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('暂不可用')
    expect(host.querySelector('.place-row')).toBeNull()
    host.querySelector<HTMLButtonElement>('[role="alert"] button')!.click()
    expect(retry).toHaveBeenCalledOnce()
    expect(host.querySelector('.place-placeholder')).toBeNull()
  })
})
