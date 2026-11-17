import { createApp, h, nextTick, onMounted, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, disposePinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import DefaultLayout from './DefaultLayout.vue'
import WriteReviewLink from '@/shared/ui/WriteReviewLink.vue'
import ReviewComposer from '@/modules/review/ui/ReviewComposer.vue'
import { useLoginStore } from '@/modules/auth/model/login.store'
import { clearAccessToken } from '@/shared/api/authSession'
import { reviewGatewayKey, unavailableReviewGateway } from '@/modules/review/model/review'
import type { ReviewPublishReceipt } from '@/modules/review/model/review'
import { restaurantFixture } from '@/modules/restaurant/api/restaurant.test-data'
import { loginRedirect } from '@/modules/auth/model/loginRedirect'
import { fetchRestaurants } from '@/modules/restaurant/api/restaurantApi'

vi.mock('@/shared/ui/layout/AppHeader.vue', () => ({ default: { template: '<header />' } }))
vi.mock('./AdminLayout.vue', () => ({ default: { template: '<aside />' } }))
vi.mock('@/modules/message/model/useRealtimeUnread', () => ({ hasUnreadNotifications: false }))
vi.mock('@/modules/restaurant/api/restaurantApi', () => ({ fetchRestaurants: vi.fn() }))

let app: App | undefined, host: HTMLDivElement
let pinia: ReturnType<typeof createPinia>, router: ReturnType<typeof createRouter>
let mounts = 0
const settle = async () => {
  for (let i = 0; i < 16; i++) await nextTick()
}
const sourcePage = {
  setup() {
    onMounted(() => mounts++)
    return () =>
      h('section', [
        h('input', { class: 'source-draft' }),
        h(WriteReviewLink, {}, () => '页内写点评'),
      ])
  },
}
const published = vi.fn()
const publish = vi.fn()
const originalShow = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal')
const originalClose = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'close')

beforeEach(() => {
  clearAccessToken()
  mounts = 0
  published.mockReset()
  publish.mockReset()
  vi.mocked(fetchRestaurants).mockResolvedValue({
    records: [restaurantFixture],
    page: 1,
    pageSize: 8,
    total: 1,
    totalPages: 1,
  })
  pinia = createPinia()
  host = document.createElement('div')
  document.body.append(host)
  Object.defineProperties(HTMLDialogElement.prototype, {
    showModal: {
      configurable: true,
      value() {
        this.open = true
      },
    },
    close: {
      configurable: true,
      value() {
        this.open = false
      },
    },
  })
})
afterEach(() => {
  app?.unmount()
  app = undefined
  host.remove()
  disposePinia(pinia)
  clearAccessToken()
  for (const [name, descriptor] of [
    ['showModal', originalShow],
    ['close', originalClose],
  ] as const) {
    if (descriptor) Object.defineProperty(HTMLDialogElement.prototype, name, descriptor)
    else Reflect.deleteProperty(HTMLDialogElement.prototype, name)
  }
})
function login() {
  useLoginStore(pinia).acceptSession({
    accessToken: 'isolated-review-navigation',
    tokenType: 'Bearer',
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id: 42, username: '测试同学', roles: ['ROLE_USER'] },
  })
}
async function mount(source: string, signedIn = true) {
  if (signedIn) login()
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/restaurants/:id',
        component: {
          render: () =>
            h(ReviewComposer, {
              restaurant: restaurantFixture,
              fixedTarget: true,
              onPublished: published,
            }),
        },
      },
      {
        path: '/canteen-windows/:id',
        component: {
          render: () =>
            h(ReviewComposer, {
              restaurant: {
                ...restaurantFixture,
                dining: {
                  kind: 'window',
                  campus: null,
                  parent: null,
                  floor: null,
                  description: null,
                  dishes: [],
                  windows: null,
                },
              },
              fixedTarget: true,
              onPublished: published,
            }),
        },
      },
      { path: '/:pathMatch(.*)*', component: sourcePage },
    ],
  })
  await router.push('/restaurant-rankings')
  await router.push(source)
  app = createApp(DefaultLayout)
    .use(pinia)
    .use(router)
    .provide(reviewGatewayKey, { ...unavailableReviewGateway, publish })
  app.mount(host)
  await settle()
}
const dialog = () => host.querySelector<HTMLDialogElement>('.review-dialog')!

it('首次路由尚未就绪时不暴露默认首页的写评入口', async () => {
  login()
  const history = createMemoryHistory()
  history.push('/account')
  router = createRouter({ history, routes: [{ path: '/account', component: sourcePage }] })
  let release!: () => void
  router.beforeEach(
    () =>
      new Promise<void>((resolve) => {
        release = resolve
      }),
  )
  app = createApp(DefaultLayout).use(pinia).use(router)
  app.mount(host)
  await vi.waitFor(() => expect(release).toBeTypeOf('function'))
  expect(host.querySelector('.compose-nav')).toBeNull()
  expect(host.textContent).toContain('正在加载页面')
  release()
  await router.isReady()
  await settle()
  expect(host.querySelector('.compose-nav')?.getAttribute('href')).toBe('/account?writeReview=1')
})

async function launch() {
  host.querySelector<HTMLAnchorElement>('.compose-nav')!.click()
  await vi.waitFor(() => expect(dialog().open).toBe(true))
  await vi.waitFor(() => expect(router.currentRoute.value.query.writeReview).toBeUndefined())
}

it('完成发布后关闭仍保留原页面，只发布一次；提交期间不能通过叉号或Esc关闭', async () => {
  let finishPublish!: (receipt: ReviewPublishReceipt) => void
  publish.mockReturnValue(
    new Promise((resolve) => {
      finishPublish = resolve
    }),
  )
  await mount('/account/reviews?page=2')
  await launch()
  const search = host.querySelector<HTMLInputElement>('.target-search-field input')!
  search.value = '测试'
  search.dispatchEvent(new Event('input'))
  host.querySelector<HTMLButtonElement>('.target-search-submit')!.click()
  await vi.waitFor(() => expect(host.querySelector('.target-search-row')).not.toBeNull())
  host.querySelector<HTMLButtonElement>('.target-search-row')!.click()
  const text = host.querySelector<HTMLTextAreaElement>('#review-body')!
  text.value = '这是原页面发布的一条真实体验'
  text.dispatchEvent(new Event('input'))
  host.querySelector<HTMLButtonElement>('.attitude button')!.click()
  host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
  await settle()
  expect(publish).toHaveBeenCalledOnce()
  expect(host.querySelector<HTMLButtonElement>('[aria-label="关闭评价"]')!.disabled).toBe(true)
  dialog().dispatchEvent(new Event('cancel', { cancelable: true }))
  expect(dialog().open).toBe(true)
  finishPublish({ reviewId: '901', restaurantId: restaurantFixture.id, replayed: false })
  await vi.waitFor(() => expect(host.querySelector('.publish-receipt')).not.toBeNull())
  host.querySelector<HTMLButtonElement>('.publish-receipt button')!.click()
  await settle()
  expect(dialog().open).toBe(false)
  expect(router.currentRoute.value.fullPath).toBe('/account/reviews?page=2')
  expect(mounts).toBe(1)
  expect(publish).toHaveBeenCalledOnce()
})

/** 用真实布局和路由覆盖全部来源类别，防止某个入口再次硬编码跳到首页。 */
it.each([
  '/account',
  '/account/reviews?page=2',
  '/account/favorites?page=3',
  '/account/contributions?status=APPROVED&pageSize=20&page=2',
  '/messages/comments',
  '/users/42?tab=favorites&page=2',
  '/map',
  '/?q=food#list',
])('从 %s 写评再关闭，页面实例、查询条件和原历史均保留', async (source) => {
  await mount(source)
  const input = host.querySelector<HTMLInputElement>('.source-draft')!
  input.value = '原页面尚未保存的内容'
  await launch()
  expect(router.currentRoute.value.fullPath).toBe(source)
  expect(host.querySelectorAll('.review-dialog')).toHaveLength(1)
  host.querySelector<HTMLButtonElement>('[aria-label="关闭评价"]')!.click()
  await settle()
  expect(dialog().open).toBe(false)
  expect(router.currentRoute.value.fullPath).toBe(source)
  expect(host.querySelector('.source-draft')).toBe(input)
  expect(input.value).toBe('原页面尚未保存的内容')
  expect(mounts).toBe(1)
  router.back()
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/restaurant-rankings'))
})

it.each(['关闭', 'Escape'])('%s 关闭与叉号使用相同机制，连续打开不会残留意图', async (action) => {
  await mount('/account')
  await launch()
  if (action === 'Escape') dialog().dispatchEvent(new Event('cancel', { cancelable: true }))
  else host.querySelector<HTMLButtonElement>('[aria-label="关闭评价"]')!.click()
  await settle()
  expect(dialog().open).toBe(false)
  expect(router.currentRoute.value.fullPath).toBe('/account')
  host.querySelector<HTMLAnchorElement>('main a')!.click()
  await vi.waitFor(() => expect(dialog().open).toBe(true))
  expect(host.querySelectorAll('dialog[open]')).toHaveLength(1)
})

it('未登录写评在认证后回原页，保留安全展示参数并仅打开一次', async () => {
  const source = '/account/contributions?status=APPROVED&pageSize=20&page=2'
  await mount(source, false)
  host.querySelector<HTMLAnchorElement>('.compose-nav')!.click()
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/login'))
  const destination = loginRedirect(router.currentRoute.value.query.redirect)
  expect(destination).toBe(source + '&writeReview=1')
  login()
  await router.replace(destination)
  await vi.waitFor(() => expect(dialog().open).toBe(true))
  await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe(source))
  host.querySelector<HTMLButtonElement>('[aria-label="关闭评价"]')!.click()
  await router.replace(source)
  expect(dialog().open).toBe(false)
})

it.each(['/restaurants/1', '/canteen-windows/1'])(
  '详情 %s 仅使用固定对象编辑器，不叠加全站弹窗',
  async (source) => {
    await mount(source)
    host.querySelector<HTMLButtonElement>('.review-open')!.click()
    await settle()
    expect(host.querySelectorAll('.review-dialog')).toHaveLength(1)
    expect(host.querySelector('.locked-target')).not.toBeNull()
    expect(host.querySelector('.review-target-search')).toBeNull()
    host.querySelector<HTMLButtonElement>('[aria-label="关闭评价"]')!.click()
    expect(router.currentRoute.value.fullPath).toBe(source)
  },
)

it('浏览器返回与换号仅清理弹窗，不产生反向跳转或再次打开', async () => {
  await mount('/account')
  await launch()
  router.back()
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/restaurant-rankings'))
  expect(dialog().open).toBe(false)
  await launch()
  useLoginStore(pinia).acceptSession({
    accessToken: 'another-isolated-user',
    tokenType: 'Bearer',
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id: 43, username: '另一同学', roles: ['ROLE_USER'] },
  })
  await settle()
  expect(dialog().open).toBe(false)
  expect(router.currentRoute.value.fullPath).toBe('/restaurant-rankings')
})

it('同一路径仅查询参数不同的历史返回也关闭弹窗，消费打开意图不会误关', async () => {
  await mount('/account?page=1')
  await router.push('/account?page=2')
  await launch()
  router.back()
  await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/account?page=1'))
  expect(dialog().open).toBe(false)
})
