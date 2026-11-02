import { createApp, nextTick, type App } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import PlaceSearchResults from './PlaceSearchResults.vue'
import type { PlaceResult } from '../model/placeSearch'

/** 真实DOM验证占位确实禁用，不借用第三方数据生成本站详情、图片或评分。 */
describe('外部地点列表和提交占位', () => {
  let app: App, host: HTMLDivElement
  afterEach(() => {
    app?.unmount()
    host?.remove()
  })
  async function setup(error: string | null = null) {
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
      loading: false,
      error,
      total: 20,
      page: 1,
      pages: 2,
      selected: null,
      onSelect: select,
      onPageChange: pageChange,
      onRetry: retry,
    })
    app.mount(host)
    await nextTick()
    return { select, pageChange, retry }
  }
  it('只展示地点基础信息，点击发出外部ID，手动添加入口不可点击', async () => {
    const { select, pageChange } = await setup()
    host.querySelector<HTMLButtonElement>('.place-row')!.click()
    expect(select).toHaveBeenCalledWith('amap:poi-1')
    expect(host.textContent).toContain('暂未匹配本站餐厅')
    expect(host.querySelector('.map-rating, img, a[href^="/restaurants/"]')).toBeNull()
    expect(host.querySelector<HTMLButtonElement>('.place-placeholder button')?.disabled).toBe(true)
    host.querySelectorAll<HTMLButtonElement>('.place-pagination button')[1]!.click()
    expect(pageChange).toHaveBeenCalledWith(2)
  })
  it('服务错误不会伪装成无结果或可提交状态，支持独立重试', async () => {
    const { retry } = await setup('高德搜索暂不可用')
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('暂不可用')
    expect(host.querySelector('.place-row')).toBeNull()
    host.querySelector<HTMLButtonElement>('[role="alert"] button')!.click()
    expect(retry).toHaveBeenCalledOnce()
    expect(host.querySelector<HTMLButtonElement>('.place-placeholder button')?.disabled).toBe(true)
  })
})
