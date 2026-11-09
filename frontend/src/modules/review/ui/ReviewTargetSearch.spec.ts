import { createApp, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, expect, it, vi } from 'vitest'
import { fetchRestaurants } from '@/modules/restaurant/api/restaurantApi'
import { restaurantFixture } from '@/modules/restaurant/api/restaurant.test-data'
import ReviewTargetSearch from './ReviewTargetSearch.vue'

vi.mock('@/modules/restaurant/api/restaurantApi', () => ({ fetchRestaurants: vi.fn() }))
let app: App, host: HTMLDivElement
const result = (name = '测试店') => ({
  records: [{ ...restaurantFixture, name }],
  page: 1,
  pageSize: 8,
  total: 1,
  totalPages: 1,
})
const settle = async () => {
  for (let i = 0; i < 12; i++) await nextTick()
}
async function mount() {
  host = document.createElement('div')
  document.body.append(host)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: ['/', '/restaurants/submit'].map((path) => ({
      path,
      component: { template: '<p />' },
    })),
  })
  await router.push('/')
  app = createApp({ render: () => h(ReviewTargetSearch) }).use(router)
  app.mount(host)
  await settle()
  return router
}
function search(term: string) {
  const input = host.querySelector('input')!
  input.value = term
  input.dispatchEvent(new Event('input'))
}
afterEach(() => {
  app?.unmount()
  host?.remove()
  vi.restoreAllMocks()
  vi.clearAllMocks()
})

/** 验证搜索请求竞争、空态与错误区分、投稿关键词传递，不向业务库写入。 */
it('ignores late responses from old keywords and cancels their requests', async () => {
  let finish!: (value: ReturnType<typeof result>) => void
  vi.mocked(fetchRestaurants)
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    .mockResolvedValue(result('新关键词店铺'))
  await mount()
  search('面馆')
  await nextTick()
  host.querySelector<HTMLButtonElement>('.target-search-submit')!.click()
  await settle()
  const signal = vi.mocked(fetchRestaurants).mock.calls[0]![3]!
  search('火锅')
  await nextTick()
  host.querySelector<HTMLButtonElement>('.target-search-submit')!.click()
  await settle()
  expect(signal.aborted).toBe(true)
  finish(result('旧结果'))
  await settle()
  expect(host.textContent).toContain('新关键词店铺')
  expect(host.textContent).not.toContain('旧结果')
  expect(vi.mocked(fetchRestaurants).mock.calls.at(-1)?.[0]).toMatchObject({
    keyword: '火锅',
    sort: 'relevance',
  })
})
it('distinguishes request errors from empty results and forwards the keyword to AMap submission', async () => {
  vi.mocked(fetchRestaurants)
    .mockRejectedValueOnce(new Error('offline'))
    .mockResolvedValue({ ...result(), records: [], total: 0, totalPages: 0 })
  const router = await mount()
  search('新店')
  await nextTick()
  host.querySelector<HTMLButtonElement>('.target-search-submit')!.click()
  await settle()
  expect(host.querySelector('[role=alert]')).not.toBeNull()
  expect(host.querySelector('.target-search-missing')).toBeNull()
  search('新店')
  await nextTick()
  host.querySelector<HTMLButtonElement>('.target-search-submit')!.click()
  await settle()
  expect(host.textContent).toContain('没有找到匹配的店铺')
  host.querySelector<HTMLButtonElement>('.target-search-missing button')!.click()
  await settle()
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/restaurants/submit'))
  expect(router.currentRoute.value.query.keyword).toBe('新店')
})
it('retries a failed next page without clearing the previous results', async () => {
  vi.mocked(fetchRestaurants)
    .mockResolvedValueOnce({ ...result('第一页'), total: 9, totalPages: 2 })
    .mockRejectedValueOnce(new Error('offline'))
    .mockResolvedValueOnce({ ...result('第二页'), page: 2, total: 9, totalPages: 2 })
  await mount()
  search('测试')
  await nextTick()
  host.querySelector<HTMLButtonElement>('.target-search-submit')!.click()
  await settle()
  host.querySelector<HTMLButtonElement>('.target-load-more')!.click()
  await settle()
  expect(host.textContent).toContain('第一页')
  expect(host.querySelector('[role=alert]')).not.toBeNull()
  host.querySelector<HTMLButtonElement>('[role=alert] button')!.click()
  await settle()
  expect(host.textContent).toContain('第一页')
  expect(host.textContent).toContain('第二页')
  expect(vi.mocked(fetchRestaurants).mock.calls.map((call) => call[1])).toEqual([1, 2, 2])
})

/** 打开、聚焦和空白提交不读取店铺，清空输入后移除结果。 */
it('only searches nonempty keywords and clears results without loading the catalog', async () => {
  vi.mocked(fetchRestaurants).mockResolvedValue(result())
  await mount()
  host.querySelector('input')!.dispatchEvent(new Event('focus'))
  host.querySelector<HTMLButtonElement>('.target-search-submit')!.click()
  await settle()
  expect(fetchRestaurants).not.toHaveBeenCalled()
  expect(host.querySelector('.target-search-results')).toBeNull()
  search('面馆')
  await nextTick()
  host.querySelector<HTMLButtonElement>('.target-search-submit')!.click()
  await settle()
  expect(host.querySelector('.target-search-row')).not.toBeNull()
  expect(host.querySelector('.target-search-missing button')?.textContent).toContain('添加餐厅')
  search('   ')
  await settle()
  host.querySelector<HTMLButtonElement>('.target-search-submit')!.click()
  await settle()
  expect(fetchRestaurants).toHaveBeenCalledTimes(1)
  expect(host.querySelector('.target-search-results')).toBeNull()
})
