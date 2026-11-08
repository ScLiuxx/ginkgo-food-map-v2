import { createApp, h, nextTick, ref } from 'vue'
import type { App, Component } from 'vue'
import { createPinia, disposePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setAccessToken, clearAccessToken } from '@/shared/api/authSession'
import { useLoginStore } from '@/modules/auth/model/login.store'
import { fetchRestaurants } from '@/modules/restaurant/api/restaurantApi'
import { restaurantFixture } from '@/modules/restaurant/api/restaurant.test-data'
import { overall, validBody, reviewGatewayKey, unavailableReviewGateway } from './model/review'
import type { ReviewGateway, ReviewItem } from './model/review'
import ReviewComposer from './ui/ReviewComposer.vue'
import ReviewPanel from './ui/ReviewPanel.vue'
import ReplyThread from './ui/ReplyThread.vue'
import type { RestaurantReadModel } from '@/modules/restaurant/model/restaurant.types'

vi.mock('@/modules/restaurant/api/restaurantApi', () => ({ fetchRestaurants: vi.fn() }))

/** 只在测试注入传输夹具，生产页面没有模拟评价、离线假成功或账号伪造入口。 */
describe('review interactions matching the original site', () => {
  let app: App
  let host: HTMLDivElement
  let pinia: ReturnType<typeof createPinia>
  let router: ReturnType<typeof createRouter>
  let gateway: ReviewGateway
  const settle = async () => {
    for (let i = 0; i < 12; i++) await nextTick()
  }
  const review: ReviewItem = {
    id: '10',
    authorId: '1',
    username: '测试社员',
    createdAt: '2026-10-29T10:00:00+08:00',
    scores: { taste: 8, value: 8, service: 8, ambience: 7.5 },
    recommended: true,
    body: '<script>正文必须按纯文本展示</script>',
    photos: [],
    canDelete: true,
    replies: [],
  }
  it('switches sorting from page one and ignores late responses from the old order', async () => {
    await mount(ReviewPanel, { restaurantId: '1' })
    const list = vi.mocked(gateway.list)
    expect(list.mock.calls[0]?.[3]).toBe('DEFAULT')
    let finishOld!: (page: Awaited<ReturnType<ReviewGateway['list']>>) => void
    list.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishOld = resolve
        }),
    )
    const button = (text: string) =>
      [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === text)!
    button('时间排序').click()
    await settle()
    const oldSignal = list.mock.calls.at(-1)![1]
    expect(list.mock.calls.at(-1)?.slice(2)).toEqual([1, 'TIME'])
    button('默认排序').click()
    await settle()
    expect(oldSignal.aborted).toBe(true)
    expect(list.mock.calls.at(-1)?.slice(2)).toEqual([1, 'DEFAULT'])
    finishOld({
      records: [{ ...review, body: '旧排序结果' }],
      page: 1,
      pageSize: 8,
      totalPages: 1,
      totalGroups: 1,
      totalReviews: 1,
    })
    await settle()
    expect(host.textContent).not.toContain('旧排序结果')
    expect(button('默认排序').getAttribute('aria-pressed')).toBe('true')
  })

  beforeEach(async () => {
    vi.resetAllMocks()
    clearAccessToken()
    pinia = createPinia()
    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        { path: '/login', component: { template: '<div />' } },
      ],
    })
    await router.push('/')
    host = document.createElement('div')
    document.body.append(host)
    gateway = {
      list: vi.fn().mockResolvedValue({
        records: [review],
        page: 1,
        pageSize: 8,
        totalGroups: 1,
        totalReviews: 1,
        totalPages: 1,
      }),
      publish: vi.fn().mockRejectedValue(new Error('网络失败')),
      reply: vi.fn().mockRejectedValue(new Error('回复网络失败')),
      remove: vi.fn().mockResolvedValue(undefined),
    }
    vi.mocked(fetchRestaurants).mockResolvedValue({
      page: 1,
      pageSize: 100,
      total: 1,
      totalPages: 1,
      records: [restaurantFixture],
    })
    // jsdom没有原生dialog方法；只补浏览器行为，不改变组件的业务逻辑。
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
      configurable: true,
      value: function (this: HTMLDialogElement) {
        this.open = true
      },
    })
    Object.defineProperty(HTMLDialogElement.prototype, 'close', {
      configurable: true,
      value: function (this: HTMLDialogElement) {
        this.open = false
      },
    })
  })
  afterEach(() => {
    app?.unmount()
    disposePinia(pinia)
    host.remove()
    clearAccessToken()
    vi.restoreAllMocks()
  })
  function login() {
    setAccessToken('test-session')
    useLoginStore(pinia).user = { id: 1, username: '测试社员', roles: ['USER'] }
  }
  /** 同一点评含本人和他人回复，用真实身份差异验证删除入口及确认流程。 */
  async function mountReplies() {
    login()
    gateway.listReplies = vi.fn().mockResolvedValue({
      records: [
        {
          id: '51',
          author: { id: '1', username: '本人' },
          body: '本人回复正文',
          createdAt: review.createdAt,
        },
        {
          id: '52',
          author: { id: '2', username: '其他人' },
          body: '他人回复正文',
          createdAt: review.createdAt,
        },
      ],
      hasMore: true,
      nextCursor: 'old-anchor',
    })
    gateway.removeReply = vi.fn().mockResolvedValue(undefined)
    await mount(ReplyThread, { reviewId: '10', username: '点评作者' })
    await clickReplyAction('查看回复')
  }
  async function clickReplyAction(label: string) {
    const button = [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === label)
    expect(button, label).toBeDefined()
    button!.click()
    await settle()
  }

  it('shows delete only for own replies, confirms, and refreshes without the old cursor', async () => {
    await mountReplies()
    expect(
      [...host.querySelectorAll('button')].filter((b) => b.textContent?.trim() === '删除回复'),
    ).toHaveLength(1)
    await clickReplyAction('删除回复')
    expect(gateway.removeReply).not.toHaveBeenCalled()
    await clickReplyAction('取消删除')
    expect(gateway.removeReply).not.toHaveBeenCalled()
    await clickReplyAction('删除回复')
    vi.mocked(gateway.listReplies!).mockResolvedValueOnce({
      records: [],
      hasMore: false,
      nextCursor: null,
    })
    await clickReplyAction('确认删除回复')
    expect(gateway.removeReply).toHaveBeenCalledWith('51', expect.any(AbortSignal))
    expect(vi.mocked(gateway.listReplies!).mock.calls.at(-1)?.[2]).toBeUndefined()
    expect(host.textContent).toContain('回复已删除')
    expect(host.textContent).not.toContain('本人回复正文')
    expect(host.textContent).toContain('还没有回复')
  })

  it('retains confirmation on delete failure and retries reading without resending a successful delete', async () => {
    await mountReplies()
    vi.mocked(gateway.removeReply!).mockRejectedValueOnce(new Error('删除未确认'))
    await clickReplyAction('删除回复')
    await clickReplyAction('确认删除回复')
    expect(host.textContent).toContain('删除未确认')
    expect(host.textContent).toContain('本人回复正文')
    vi.mocked(gateway.listReplies!).mockRejectedValueOnce(new Error('刷新失败'))
    await clickReplyAction('确认删除回复')
    expect(host.textContent).toContain('回复已删除，但列表刷新失败')
    expect(host.textContent).not.toContain('本人回复正文')
    await clickReplyAction('重试加载回复')
    expect(gateway.removeReply).toHaveBeenCalledTimes(2)
  })

  it('aborts deletion on account change and ignores the old account receipt', async () => {
    await mountReplies()
    let resolveDelete!: () => void
    vi.mocked(gateway.removeReply!).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveDelete = resolve
        }),
    )
    await clickReplyAction('删除回复')
    await clickReplyAction('确认删除回复')
    const signal = vi.mocked(gateway.removeReply!).mock.calls[0]![1]
    useLoginStore(pinia).user = { id: 2, username: '另一个账号', roles: ['USER'] }
    setAccessToken('another-session')
    await settle()
    expect(signal.aborted).toBe(true)
    expect(host.textContent).not.toContain('确认删除回复')
    const reads = vi.mocked(gateway.listReplies!).mock.calls.length
    resolveDelete()
    await settle()
    expect(host.textContent).not.toContain('回复已删除')
    expect(gateway.listReplies).toHaveBeenCalledTimes(reads)
  })
  async function mount(component: Component, props = {}) {
    app = createApp({ render: () => h(component, props) })
      .use(pinia)
      .use(router)
      .provide(reviewGatewayKey, gateway)
    app.mount(host)
    await settle()
  }
  async function open() {
    host.querySelector<HTMLButtonElement>('.review-open')!.click()
    await settle()
  }
  async function fill(text: string) {
    const field = host.querySelector<HTMLTextAreaElement>('textarea')!
    field.value = text
    field.dispatchEvent(new Event('input'))
    await settle()
  }
  async function submit() {
    host
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await settle()
  }

  /** 隔离夹具模拟未来真实关联；不向正式目录添加窗口或业务记录。 */
  function canteen(id = 'canteen'): RestaurantReadModel {
    return {
      ...restaurantFixture,
      id,
      category: '食堂',
      dining: {
        kind: 'canteen',
        campus: null,
        parent: null,
        floor: null,
        description: null,
        dishes: [],
        windows: [1, 2].map((n) => ({
          id: `${id}-w${n}`,
          canteenId: id,
          name: `测试窗口${n}`,
          floor: `${n}楼`,
          category: null,
          score: null,
          reviewCount: 0,
          price: null,
          closed: false,
          specialties: [],
        })),
      },
    }
  }
  async function choose(label: string, value: string) {
    const select = host.querySelector<HTMLSelectElement>(`select[aria-label="${label}"]`)!
    select.value = value
    select.dispatchEvent(new Event('change'))
    await settle()
  }

  it('requires a canteen window, clears cascading selections and publishes only its stable ID', async () => {
    login()
    vi.mocked(fetchRestaurants).mockResolvedValue({
      page: 1,
      pageSize: 100,
      total: 2,
      totalPages: 1,
      records: [canteen(), canteen('second')],
    })
    await mount(ReviewComposer)
    await open()
    await fill('窗口独立评价不会计入父食堂')
    expect(host.querySelector<HTMLButtonElement>('[type=submit]')!.disabled).toBe(true)
    await submit()
    expect(gateway.publish).not.toHaveBeenCalled()
    await choose('楼层', '1楼')
    await choose('窗口', 'canteen-w1')
    expect(host.querySelector<HTMLButtonElement>('[type=submit]')!.disabled).toBe(false)
    await submit()
    expect(vi.mocked(gateway.publish).mock.calls[0]![0].restaurantId).toBe('canteen-w1')
    await choose('楼层', '2楼')
    expect(host.querySelector<HTMLSelectElement>('[aria-label="窗口"]')!.value).toBe('')
    await choose('窗口', 'canteen-w2')
    await choose('评价对象', 'second')
    expect(host.querySelector<HTMLSelectElement>('[aria-label="楼层"]')!.value).toBe('')
    expect(host.querySelector<HTMLSelectElement>('[aria-label="窗口"]')!.value).toBe('')
    expect(host.querySelector<HTMLButtonElement>('[type=submit]')!.disabled).toBe(true)
  })

  it('blocks canteens without windows and excludes foreign or closed windows', async () => {
    login()
    const record = canteen()
    record.dining!.windows = record.dining!.windows!.map((w, i) =>
      i ? { ...w, canteenId: 'foreign' } : { ...w, closed: true },
    )
    await mount(ReviewComposer, { restaurant: record, fixedTarget: true })
    await open()
    expect(host.textContent).toContain('该食堂暂无收录窗口')
    expect(host.querySelector<HTMLButtonElement>('[type=submit]')!.disabled).toBe(true)
    await fill('食堂没有窗口时不得向父级发布')
    await submit()
    expect(gateway.publish).not.toHaveBeenCalled()
  })

  it('locks a window detail with its parent and floor and maps half stars to legacy scores', async () => {
    login()
    const record = {
      ...restaurantFixture,
      id: 'window-id',
      name: '测试独立窗口',
      dining: {
        kind: 'window' as const,
        campus: null,
        parent: { id: 'canteen', name: '测试食堂' },
        floor: '二楼',
        description: null,
        windows: null,
        dishes: [],
      },
    }
    await mount(ReviewComposer, { restaurant: record })
    await open()
    expect(fetchRestaurants).not.toHaveBeenCalled()
    expect(host.querySelector('.locked-target')?.textContent).toContain(
      '测试食堂 · 二楼 · 测试独立窗口',
    )
    const star = host.querySelector<HTMLInputElement>('[type=range]')!
    star.value = '4.5'
    star.dispatchEvent(new Event('input'))
    await settle()
    expect(host.querySelector('output')?.textContent).toBe('4.5')
    await fill('锁定窗口的半星评价测试')
    await submit()
    expect(vi.mocked(gateway.publish).mock.calls[0]![0]).toMatchObject({
      restaurantId: 'window-id',
      scores: { taste: 9, value: 7, service: 7, ambience: 7 },
    })
  })

  it('rounds half up, distinguishes zero, and counts Unicode code points', () => {
    expect(overall(review.scores)).toBe(7.88)
    expect(overall({ taste: 0, value: 0, service: 0, ambience: 0 })).toBe(0)
    expect(validBody(' 😀😀😀😀😀 ', 5, 500)).toBe(true)
    expect(validBody('     ', 1, 200)).toBe(false)
    expect(validBody('字'.repeat(501), 5, 500)).toBe(false)
  })
  it('filters recommended and photo reviews across every page and displays five-star scores', async () => {
    const second = {
      ...review,
      id: 'page-two',
      body: '第二页的有图推荐正文',
      overallScore: 9,
      photos: [{ id: 'photo', url: '/test-only.jpg' }],
    }
    vi.mocked(gateway.list).mockImplementation(async (_id, _signal, page = 1) => ({
      records: page === 1 ? [{ ...review, recommended: false, body: '第一页普通点评' }] : [second],
      page,
      pageSize: 1,
      totalGroups: 2,
      totalReviews: 2,
      totalPages: 2,
    }))
    await mount(ReviewPanel, { restaurantId: '1', starScale: true })
    const filter = async (name: string) => {
      const button = [...host.querySelectorAll<HTMLButtonElement>('.content-filters button')].find(
        (el) => el.textContent?.trim() === name,
      )!
      button.click()
      await settle()
    }
    await filter('推荐')
    expect(vi.mocked(gateway.list).mock.calls.some((call) => call[2] === 2)).toBe(true)
    expect(host.querySelectorAll('.review-card')).toHaveLength(1)
    expect(host.querySelector('.body')?.textContent).toBe(second.body)
    expect(host.querySelector('.star-score strong')?.textContent).toBe('4.5')
    await filter('有图')
    expect(host.querySelector('.body')?.textContent).toBe(second.body)
    await filter('全部')
    expect(host.querySelector('.body')?.textContent).toBe('第一页普通点评')
  })
  it('shows an error for an incomplete filtered traversal instead of presenting partial matches', async () => {
    vi.mocked(gateway.list).mockImplementation(async (_id, _signal, page = 1) => {
      if (page === 2) throw new Error('后续页读取失败')
      return {
        records: [review],
        page: 1,
        pageSize: 1,
        totalGroups: 2,
        totalReviews: 2,
        totalPages: 2,
      }
    })
    await mount(ReviewPanel, { restaurantId: '1', starScale: true })
    host.querySelectorAll<HTMLButtonElement>('.content-filters button')[1]!.click()
    await settle()
    expect(host.querySelector('[role=alert]')?.textContent).toContain('后续页读取失败')
    expect(host.querySelectorAll('.review-card')).toHaveLength(0)
  })
  it('redirects guests to login and preserves the write-review return target', async () => {
    await mount(ReviewComposer)
    await open()
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/login'))
    expect(router.currentRoute.value.query.redirect).toBe('/?writeReview=1')
    expect(fetchRestaurants).not.toHaveBeenCalled()
  })
  it('loads all restaurant pages, including closed restaurants, with original defaults', async () => {
    login()
    vi.mocked(fetchRestaurants)
      .mockResolvedValueOnce({
        page: 1,
        pageSize: 100,
        total: 101,
        totalPages: 2,
        records: [restaurantFixture],
      })
      .mockResolvedValueOnce({
        page: 2,
        pageSize: 100,
        total: 101,
        totalPages: 2,
        records: [{ ...restaurantFixture, id: '2', name: '停业店', closed: true }],
      })
    await mount(ReviewComposer)
    await open()
    expect(fetchRestaurants).toHaveBeenCalledTimes(2)
    expect(host.querySelector('select')!.textContent).toContain('停业店（已停业）')
    expect(
      [...host.querySelectorAll<HTMLInputElement>('input[type=range]')].map((el) => [
        el.value,
        el.min,
        el.max,
        el.step,
      ]),
    ).toEqual(Array(4).fill(['3.5', '0', '5', '0.5']))
    expect(host.querySelector('[aria-pressed=true]')?.textContent?.trim()).toBe('推荐')
    expect(host.textContent).toContain('发布账号：测试社员')
  })
  it('consumes the login return intent once without automatically publishing', async () => {
    login()
    await router.push('/?writeReview=1')
    await mount(ReviewComposer)
    await vi.waitFor(() => expect(router.currentRoute.value.query.writeReview).toBeUndefined())
    expect(host.querySelector('dialog')!.open).toBe(true)
    expect(gateway.publish).not.toHaveBeenCalled()
    host.querySelector<HTMLButtonElement>('[aria-label="关闭评价"]')!.click()
    await settle()
    expect(host.querySelector('dialog')!.open).toBe(false)
  })
  it('locks detail publication to the window ID without fetching or selecting a parent restaurant', async () => {
    login()
    await mount(ReviewComposer, {
      restaurant: { ...restaurantFixture, id: '201', name: '测试窗口' },
      fixedTarget: true,
      targetHint: '只评价这个窗口',
    })
    await open()
    expect(fetchRestaurants).not.toHaveBeenCalled()
    expect(host.querySelector<HTMLSelectElement>('select')?.disabled).toBe(true)
    expect(host.querySelector<HTMLSelectElement>('select')?.value).toBe('201')
    expect(host.textContent).toContain('只评价这个窗口')
    await fill('测试窗口的独立评价正文')
    await submit()
    expect(vi.mocked(gateway.publish).mock.calls[0]?.[0].restaurantId).toBe('201')
    expect(host.querySelector('.add-missing')).toBeNull()
  })
  it('validates text, keeps failed drafts and retry IDs, and renews IDs for changed content', async () => {
    login()
    await mount(ReviewComposer)
    await open()
    await submit()
    expect(gateway.publish).not.toHaveBeenCalled()
    await fill('这是一条真实用餐体验')
    await submit()
    await submit()
    const calls = vi.mocked(gateway.publish).mock.calls
    expect(calls[0]![0].clientRequestId).toBe(calls[1]![0].clientRequestId)
    expect(host.querySelector('textarea')!.value).toBe('这是一条真实用餐体验')
    expect(host.textContent).toContain('网络失败')
    await fill('更新过的真实用餐体验')
    await submit()
    expect(calls[2]![0].clientRequestId).not.toBe(calls[0]![0].clientRequestId)
    clearAccessToken()
    await settle()
    expect(host.querySelector('dialog')!.open).toBe(true)
    expect(host.querySelector<HTMLButtonElement>('.publish')!.disabled).toBe(true)
    expect(host.querySelector('textarea')!.value).toBe('更新过的真实用餐体验')
    expect(host.querySelector<HTMLTextAreaElement>('.locked-message textarea')!.readOnly).toBe(true)
  })
  it('keeps validation, pending and server errors next to the actual submit button', async () => {
    login()
    await mount(ReviewComposer)
    await open()
    const button = host.querySelector<HTMLButtonElement>('button[type=submit]')!
    button.click()
    await settle()
    expect(gateway.publish).not.toHaveBeenCalled()
    expect(host.querySelector('footer [role=alert]')?.textContent).toContain('请填写5–500字')
    await fill('这是一条提交中的真实用餐体验')
    let reject!: (reason: Error) => void
    vi.mocked(gateway.publish).mockReturnValue(
      new Promise((_, fail) => {
        reject = fail
      }),
    )
    button.click()
    await settle()
    expect(gateway.publish).toHaveBeenCalledTimes(1)
    expect(host.querySelector('footer [role=status]')?.textContent).toContain('正在发布点评')
    const cancel = [...host.querySelectorAll<HTMLButtonElement>('footer button')].find(
      (el) => el.textContent?.trim() === '取消',
    )!
    expect(cancel.disabled).toBe(true)
    cancel.click()
    expect(host.querySelector('dialog')!.open).toBe(true)
    reject(new Error('网络失败'))
    await settle()
    expect(host.querySelector('footer [role=alert]')?.textContent).toContain('网络失败')
    expect(button.disabled).toBe(false)
  })
  it('shows the server receipt until acknowledged and prevents premature refresh', async () => {
    login()
    vi.mocked(gateway.publish).mockResolvedValue({
      reviewId: '321',
      restaurantId: restaurantFixture.id,
      replayed: true,
    })
    await mount(ReviewComposer)
    await open()
    await fill('这是一条成功的用餐体验')
    await submit()
    expect(host.querySelector('.publish-receipt')?.textContent).toContain('已确认此前发布成功')
    expect(host.querySelector('.receipt-id')?.textContent).toBe('321')
    expect(host.querySelector('dialog')!.open).toBe(true)
    expect(host.querySelector('form')).toBeNull()
    host.querySelector<HTMLButtonElement>('.publish-receipt button')!.click()
    await settle()
    expect(host.querySelector('dialog')!.open).toBe(false)
  })
  it('marks text-only capability and removes misleading restaurant predictions', async () => {
    login()
    gateway.supportsPhotos = false
    await mount(ReviewComposer)
    await open()
    expect(host.querySelector<HTMLInputElement>('input[type=file]')!.disabled).toBe(true)
    expect(host.textContent).toContain('本次仅支持文字点评')
    expect(host.textContent).not.toContain('预测综合分')
  })
  it('keeps the dialog and fields frozen while the publish request is in flight', async () => {
    login()
    let resolve!: () => void
    vi.mocked(gateway.publish).mockReturnValue(
      new Promise<void>((r) => {
        resolve = r
      }),
    )
    await mount(ReviewComposer)
    await open()
    await fill('这是一条提交中的用餐体验')
    await submit()
    expect(host.querySelector<HTMLFieldSetElement>('.review-inputs')!.disabled).toBe(true)
    expect(host.querySelector<HTMLButtonElement>('[aria-label="关闭评价"]')!.disabled).toBe(true)
    host.querySelector('dialog')!.dispatchEvent(new Event('cancel', { cancelable: true }))
    await settle()
    expect(host.querySelector('dialog')!.open).toBe(true)
    resolve()
    await settle()
    expect(host.querySelector('dialog')!.open).toBe(false)
  })
  it('renders safe cards, retains failed replies and reuses the same retry ID', async () => {
    login()
    await mount(ReviewPanel, { restaurantId: '1' })
    expect(host.querySelector('.review-card')!.textContent).toContain('7.88')
    expect(host.querySelector('.body')!.textContent).toBe(review.body)
    expect(host.querySelector('script')).toBeNull()
    const input = host.querySelector('input')!
    input.value = '谢谢分享'
    input.dispatchEvent(new Event('input'))
    await settle()
    await submit()
    await submit()
    expect(gateway.reply).toHaveBeenCalledTimes(2)
    expect(vi.mocked(gateway.reply).mock.calls[0]![2]).toBe(
      vi.mocked(gateway.reply).mock.calls[1]![2],
    )
    expect(input.value).toBe('谢谢分享')
    const event = new KeyboardEvent('keydown', {
      key: 'Enter',
      isComposing: true,
      cancelable: true,
      bubbles: true,
    })
    input.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)
  })
  it('requires confirmation before deletion and refreshes after success', async () => {
    login()
    await mount(ReviewPanel, { restaurantId: '1' })
    const button = [...host.querySelectorAll('button')].find(
      (el) => el.textContent?.trim() === '删除评价',
    )!
    button.click()
    await settle()
    expect(gateway.remove).not.toHaveBeenCalled()
    const confirm = [...document.querySelectorAll('button')].find(
      (el) => el.textContent?.trim() === '确认删除',
    )!
    confirm.click()
    await settle()
    expect(gateway.remove).toHaveBeenCalledWith('10')
    expect(gateway.list).toHaveBeenCalledTimes(2)
  })
  it('shows deletion only for the authenticated author, not matching names or flags', async () => {
    await mount(ReviewPanel, { restaurantId: '1' })
    const hasDelete = () =>
      [...host.querySelectorAll('button')].some((b) => b.textContent?.trim() === '删除评价')
    expect(hasDelete()).toBe(false)
    login()
    await settle()
    expect(hasDelete()).toBe(true)
    useLoginStore(pinia).user = { id: 2, username: review.username, roles: ['ADMIN'] }
    await settle()
    expect(hasDelete()).toBe(false)
    clearAccessToken()
    await settle()
    expect(hasDelete()).toBe(false)
  })
  it('keeps failed deletion retryable inside confirmation and cancellation does not delete', async () => {
    login()
    vi.mocked(gateway.remove).mockRejectedValueOnce(new Error('暂时不可用'))
    await mount(ReviewPanel, { restaurantId: '1' })
    const click = async (name: string) => {
      ;[...document.querySelectorAll<HTMLButtonElement>('button')]
        .find((b) => b.textContent?.trim() === name)!
        .click()
      await settle()
    }
    await click('删除评价')
    await click('取消')
    expect(gateway.remove).not.toHaveBeenCalled()
    await click('删除评价')
    await click('确认删除')
    expect(document.querySelector('dialog[open]')?.textContent).toContain('暂时不可用')
    expect(host.textContent).toContain(review.body)
    await click('确认删除')
    expect(gateway.remove).toHaveBeenCalledTimes(2)
    expect(document.querySelector('dialog[open]')).toBeNull()
    expect(vi.mocked(gateway.list).mock.calls.at(-1)?.[2]).toBe(1)
  })
  it('paginates author groups and resets to page one on refresh or publication', async () => {
    gateway.supportsReplies = false
    gateway.list = vi.fn().mockImplementation(async (_id, _signal, page = 1) => ({
      records: [{ ...review, id: String(page), historyCount: 2, canDelete: false }],
      page,
      pageSize: 8,
      totalGroups: 9,
      totalReviews: 12,
      totalPages: 2,
    }))
    const revision = ref(0)
    await mount({
      setup: () => () => h(ReviewPanel, { restaurantId: '1', revision: revision.value }),
    })
    expect(host.textContent).toContain('9位同学 · 12条点评')
    expect(host.textContent).toContain('2条历史点评')
    expect(host.querySelector('form')).toBeNull()
    const click = async (name: string) => {
      ;[...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === name)!.click()
      await settle()
    }
    await click('下一页点评')
    expect(host.textContent).toContain('第2 / 2页')
    await click('刷新评价')
    expect(host.textContent).toContain('第1 / 2页')
    await click('下一页点评')
    revision.value++
    await settle()
    expect(host.textContent).toContain('第1 / 2页')
    expect(vi.mocked(gateway.list).mock.calls.map((c) => c[2])).toEqual([1, 2, 1, 2, 1])
  })
  it('retries the failed page without displaying a false empty state', async () => {
    const data = (page: number) => ({
      records: [review],
      page,
      pageSize: 8,
      totalGroups: 9,
      totalReviews: 9,
      totalPages: 2,
    })
    gateway.list = vi
      .fn()
      .mockResolvedValueOnce(data(1))
      .mockRejectedValueOnce(new Error('网络失败'))
      .mockResolvedValueOnce(data(2))
    await mount(ReviewPanel, { restaurantId: '1' })
    ;[...host.querySelectorAll('button')]
      .find((b) => b.textContent?.trim() === '下一页点评')!
      .click()
    await settle()
    expect(host.querySelector('[role=alert]')?.textContent).toContain('网络失败')
    expect(host.querySelector('.empty')).toBeNull()
    ;[...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === '重试')!.click()
    await settle()
    expect(host.textContent).toContain('第2 / 2页')
    expect(vi.mocked(gateway.list).mock.calls.map((c) => c[2])).toEqual([1, 2, 2])
  })
  it('aborts the previous restaurant and ignores its late response', async () => {
    const pending: { resolve: (value: unknown) => void; signal: AbortSignal }[] = []
    gateway.list = vi
      .fn()
      .mockImplementation(
        (_id, signal) => new Promise((resolve) => pending.push({ resolve, signal })),
      )
    const id = ref('1')
    const photos = vi.fn()
    await mount({ setup: () => () => h(ReviewPanel, { restaurantId: id.value, onPhotos: photos }) })
    id.value = '2'
    await settle()
    expect(pending[0]!.signal.aborted).toBe(true)
    const data = (body: string) => ({
      records: [{ ...review, body }],
      page: 1,
      pageSize: 8,
      totalGroups: 1,
      totalReviews: 1,
      totalPages: 1,
    })
    pending[1]!.resolve(data('新餐厅点评'))
    await settle()
    pending[0]!.resolve(data('旧餐厅点评'))
    await settle()
    expect(host.textContent).toContain('新餐厅点评')
    expect(host.textContent).not.toContain('旧餐厅点评')
    expect(photos.mock.calls.map(([update]) => [update.restaurantId, update.state])).toEqual([
      ['1', 'loading'],
      ['2', 'loading'],
      ['2', 'ready'],
    ])
  })
  it('fails explicitly when real review transport is not available', async () => {
    await expect(
      unavailableReviewGateway.publish({
        restaurantId: '1',
        scores: review.scores,
        recommended: true,
        body: '五个字以上',
        photos: [],
        clientRequestId: 'test',
      }),
    ).rejects.toThrow('尚未开放')
  })
})
