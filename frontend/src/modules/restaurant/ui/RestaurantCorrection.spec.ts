import { captureLinkTarget } from '@/shared/test/captureLinkTarget'
import { mockDialog } from '@/shared/test/mockDialog'
import { createApp, nextTick, type App } from 'vue'
import { createPinia, disposePinia, setActivePinia } from 'pinia'
import { useLoginStore } from '@/modules/auth'
import {
  fetchMyRequestDetail,
  fetchMyRequestPhoto,
  fetchMyRequestReviewPhoto,
  editMyRequest,
  resubmitMyRequest,
  type MyRequestDetail,
} from '../api/myRestaurantRequestsApi'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearAccessToken, setAccessToken } from '@/shared/api/authSession'
import {
  fetchCorrectionSource,
  fetchRestaurantCategories,
  submitRestaurantCorrection,
  submitNewRestaurant,
  createRestaurant,
  updateRestaurant,
  fetchRestaurantManagement,
} from '../api/restaurantApi'
import { categoryFixtures, responseFixture } from '../api/restaurant.test-data'
import { prepareMerchantSubmission } from '../model/merchantSubmissionDraft'
import RestaurantCorrection from './RestaurantCorrection.vue'
import { uploadImage, cancelImage } from '@/modules/file/api/mediaApi'
import { compressPhoto } from '@/modules/file/model/photos'
import { searchAMapPlaces } from '@/modules/map/sdk/places'
import { loadAMap } from '@/modules/map/sdk/amap'
import { fetchRestaurantAlbum, fetchRestaurantAlbumImage } from '../api/restaurantAlbumApi'

vi.mock('../api/restaurantAlbumApi', () => ({
  fetchRestaurantAlbum: vi.fn(),
  fetchRestaurantAlbumImage: vi.fn(),
}))

vi.mock('@/modules/file/api/mediaApi', () => ({ uploadImage: vi.fn(), cancelImage: vi.fn() }))
vi.mock('@/modules/file/model/photos', () => ({ compressPhoto: vi.fn() }))

vi.mock('@/modules/map/sdk/places', () => ({ searchAMapPlaces: vi.fn() }))
vi.mock('@/modules/map/sdk/amap', () => ({ loadAMap: vi.fn() }))
vi.mock('../api/myRestaurantRequestsApi', async (original) => ({
  ...(await original<typeof import('../api/myRestaurantRequestsApi')>()),
  fetchMyRequestDetail: vi.fn(),
  fetchMyRequestPhoto: vi.fn(),
  fetchMyRequestReviewPhoto: vi.fn(),
  editMyRequest: vi.fn(),
  resubmitMyRequest: vi.fn(),
}))

vi.mock('../api/restaurantApi', () => ({
  fetchCorrectionSource: vi.fn(),
  fetchRestaurantCategories: vi.fn(),
  submitRestaurantCorrection: vi.fn(),
  submitNewRestaurant: vi.fn(),
  createRestaurant: vi.fn(),
  updateRestaurant: vi.fn(),
  fetchRestaurantManagement: vi.fn(),
}))

describe('商户资料补充与纠错', () => {
  let app: App, host: HTMLDivElement
  let pinia: ReturnType<typeof createPinia>
  let restoreDialog: () => void
  const settle = async () => {
    for (let i = 0; i < 10; i++) await nextTick()
  }
  beforeEach(() => {
    restoreDialog = mockDialog()
    vi.resetAllMocks()
    pinia = createPinia()
    setActivePinia(pinia)
    setAccessToken('correction-user')
    vi.stubGlobal(
      'URL',
      class extends URL {
        static createObjectURL = vi.fn(() => `blob:${crypto.randomUUID()}`)
        static revokeObjectURL = vi.fn()
      },
    )
    vi.mocked(compressPhoto).mockImplementation(async (file) => file)
    vi.mocked(fetchRestaurantAlbum).mockResolvedValue({ records: [], nextCursor: null })
    vi.mocked(fetchRestaurantAlbumImage).mockResolvedValue(
      new Blob(['jpeg'], { type: 'image/jpeg' }),
    )
    vi.mocked(uploadImage).mockResolvedValue('101')
    vi.mocked(cancelImage).mockResolvedValue(undefined)
    vi.mocked(fetchCorrectionSource).mockResolvedValue({
      ...responseFixture,
      name: '原店名',
      latitude: 30.75,
      longitude: 103.93,
      restaurantType: 'REGULAR',
      supportsDineIn: null,
      supportsDelivery: null,
    })
    vi.mocked(fetchRestaurantCategories).mockResolvedValue([
      ...categoryFixtures,
      { code: 'OTHER', name: '其他', sortOrder: 99, enabled: true },
    ])
    vi.mocked(submitRestaurantCorrection).mockResolvedValue({
      id: '51',
      status: 'PENDING',
    })
    vi.mocked(searchAMapPlaces).mockResolvedValue({
      places: [
        {
          id: 'amap-1',
          name: '高德分店',
          address: '新的商户地址',
          district: '成都市',
          category: '餐饮',
          latitude: 30.6757894,
          longitude: 104.0996712,
        },
      ],
      total: 1,
    })
    host = document.createElement('div')
    document.body.append(host)
  })
  afterEach(() => {
    app?.unmount()
    disposePinia(pinia)
    host.remove()
    clearAccessToken()
    vi.unstubAllGlobals()
    restoreDialog()
  })
  async function mount(path = '/restaurants/19/correction', previous?: string) {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: '/account/contributions/:requestId/edit',
          component: RestaurantCorrection,
          props: (route) => ({ requestId: route.params.requestId, submission: true }),
        },
        { path: '/account/contributions', component: { template: '<div />' } },
        {
          path: '/admin/restaurants/new',
          component: RestaurantCorrection,
          props: { admin: true, submission: true },
        },
        {
          path: '/admin/restaurants/:id/edit',
          component: RestaurantCorrection,
          props: (route) => ({ id: route.params.id, admin: true }),
        },
        { path: '/admin/restaurants', component: { template: '<div />' } },
        {
          path: '/restaurants/submit',
          component: RestaurantCorrection,
          props: { submission: true },
        },
        {
          path: '/restaurants/:id/correction',
          component: RestaurantCorrection,
          props: true,
        },
        { path: '/login', component: { template: '<div />' } },
        { path: '/restaurants/:id', component: { template: '<div />' } },
      ],
    })
    if (previous) await router.push(previous)
    await router.push(path)
    // 内存历史不会自动生成浏览器的back字段，显式补齐来源现场。
    if (previous) router.options.history.replace(path, { back: previous })
    app = createApp(RouterView).use(router).use(pinia)
    app.mount(host)
    await settle()
    return router
  }
  it('投稿未提交时用站内弹窗确认，取消保留资料，确认才离开', async () => {
    const router = await mount('/restaurants/submit')
    await field('商户名称', '未提交的门店')
    const cancel = router.push('/account/contributions')
    await settle()
    const dialog = document.querySelector<HTMLDialogElement>('.leave-confirmation')!
    expect(dialog.open).toBe(true)
    dialog.querySelector<HTMLButtonElement>('button')!.click()
    await cancel
    expect(router.currentRoute.value.path).toBe('/restaurants/submit')
    expect(host.querySelector<HTMLInputElement>('input[type="search"]')!.value).toBe('未提交的门店')
    const leave = router.push('/account/contributions')
    await settle()
    dialog.querySelector<HTMLButtonElement>('.leave')!.click()
    await leave
    expect(router.currentRoute.value.path).toBe('/account/contributions')
    expect(submitNewRestaurant).not.toHaveBeenCalled()
  })

  async function field(name: string, value: string | boolean) {
    const label = [...host.querySelectorAll('label')].find((l) =>
      l.textContent?.trim().startsWith(name),
    )!
    const el = label.control as HTMLInputElement
    if (typeof value === 'boolean') el.checked = value
    else el.value = value
    el.dispatchEvent(
      new Event(el.tagName === 'SELECT' || typeof value === 'boolean' ? 'change' : 'input', {
        bubbles: true,
      }),
    )
    await settle()
  }
  async function submit() {
    host
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await settle()
  }
  async function clickButton(text: string) {
    ;[...host.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.trim() === text)!
      .click()
    await settle()
  }
  async function chooseReviewPhoto() {
    const input = host.querySelector<HTMLInputElement>('[aria-label="选择点评照片"]')!
    Object.defineProperty(input, 'files', {
      configurable: true,
      value: [new File(['jpeg'], 'food.jpg', { type: 'image/jpeg' })],
    })
    input.dispatchEvent(new Event('change', { bubbles: true }))
    await settle()
  }
  async function choosePhoto() {
    const input = host.querySelector<HTMLInputElement>('input[type="file"]')!
    Object.defineProperty(input, 'files', {
      configurable: true,
      value: [new File(['jpeg'], 'merchant.jpg', { type: 'image/jpeg' })],
    })
    input.dispatchEvent(new Event('change', { bubbles: true }))
    await settle()
  }
  /** 同时覆盖图片用途隔离、必选态度以及网络重试，避免联合表单仅看起来可提交。 */
  it('联合投稿态度无默认值，图片分用途，失败重试保留完整请求编号', async () => {
    const draft = prepareMerchantSubmission({
      name: '新店',
      address: '校园路',
      latitude: 30.75,
      longitude: 103.93,
    })!
    await mount(`/restaurants/submit?draft=${draft}`)
    await field('美食品类', 'OTHER')
    vi.mocked(uploadImage).mockImplementation(async (_file, _owner, purpose) =>
      purpose === 'REVIEW' ? '202' : '101',
    )
    await choosePhoto()
    await clickButton('下一步，写点评 →')
    expect(submitNewRestaurant).not.toHaveBeenCalled()
    expect(host.querySelector('.restaurant-fields')?.getAttribute('style')).toContain(
      'display: none',
    )
    expect(host.querySelectorAll('.choices [aria-pressed="true"]')).toHaveLength(0)
    await submit()
    expect(submitNewRestaurant).not.toHaveBeenCalled()
    expect(host.textContent).toContain('请选择推荐态度')
    await field('说说你的用餐体验', '好')
    ;[...host.querySelectorAll<HTMLButtonElement>('.choices button')]
      .find((b) => b.textContent?.trim() === '一般')!
      .click()
    await chooseReviewPhoto()
    vi.mocked(submitNewRestaurant)
      .mockRejectedValueOnce(new Error('网络失败'))
      .mockResolvedValue({ id: '71', status: 'PENDING' })
    await submit()
    const first = vi.mocked(submitNewRestaurant).mock.calls[0]![0]
    expect(first).toMatchObject({
      photoIds: ['101'],
      review: {
        recommendation: 'NEUTRAL',
        body: '好',
        photoIds: ['202'],
      },
    })
    await submit()
    expect(vi.mocked(submitNewRestaurant).mock.calls[1]![0]).toEqual(first)
    expect(cancelImage).not.toHaveBeenCalled()
    expect(host.textContent).toContain('附带点评已保存')
  })
  function requestFixture(status: 'PENDING' | 'REJECTED' = 'PENDING'): MyRequestDetail {
    return {
      userId: '42',
      application: {
        id: '71',
        requestType: 'CREATE',
        status,
        name: '原投稿店铺',
        restaurantId: null,
        createdAt: '2026-11-14T05:00:00Z',
        reviewedAt: status === 'REJECTED' ? '2026-11-14T06:00:00Z' : null,
        photoCount: 1,
      },
      proposed: {
        ...responseFixture,
        name: '原投稿店铺',
        latitude: 30.75,
        longitude: 103.93,
        locationConfirmed: true,
        tags: [],
      },
      approved: null,
      rejectReason: status === 'REJECTED' ? '位置不准确' : null,
      targetAvailable: false,
      photoPositions: [0],
      reviewSubmission: {
        id: '81',
        status: 'WAITING',
        scores: { taste: 7.5, value: 8, service: 7, ambience: 7 },
        recommendation: 'NEUTRAL',
        body: '这是本人保存的待发布点评。',
        photoPositions: [0],
        publishedReviewId: null,
      },
    }
  }
  /** 真正切换视图再重建编辑器，确认草稿与上传队列没有随子组件卸载而丢失。 */
  it('两步往返保留店铺资料、点评、评分及两类照片，直到最后才联合提交', async () => {
    const draft = prepareMerchantSubmission({
      name: '两步测试店',
      address: '校园路',
      latitude: 30.75,
      longitude: 103.93,
    })!
    await mount(`/restaurants/submit?draft=${draft}`)
    await field('美食品类', 'OTHER')
    await field('每行一个标签', '好吃\n实惠')
    vi.mocked(uploadImage).mockImplementation(async (_file, _owner, purpose) =>
      purpose === 'REVIEW' ? '202' : '101',
    )
    await choosePhoto()
    // 第一步的回车只能进入点评页，不能隐式提交店铺。
    await submit()
    expect(submitNewRestaurant).not.toHaveBeenCalled()
    expect(document.activeElement).toBe(host.querySelector('h1'))
    await field('说说你的用餐体验', '两次返回之后这条点评仍然存在。')
    await clickButton('一般')
    const score = host.querySelector<HTMLInputElement>('#attached-taste')!
    score.value = '4.5'
    score.dispatchEvent(new Event('input', { bubbles: true }))
    await chooseReviewPhoto()
    await clickButton('← 返回店铺资料')
    expect(host.querySelector('.review-step')).toBeNull()
    expect(host.querySelector<HTMLInputElement>('input')!.value).toBe('两步测试店')
    expect(host.querySelector<HTMLTextAreaElement>('textarea')!.value).toBe('好吃\n实惠')
    expect(host.querySelector('.merchant-photo img')).not.toBeNull()
    await field('地址', '校园路一号')
    await clickButton('下一步，写点评 →')
    expect(host.querySelector('.shop-summary')?.textContent).toContain('校园路一号')
    expect(host.querySelector<HTMLTextAreaElement>('.body-label textarea')!.value).toBe(
      '两次返回之后这条点评仍然存在。',
    )
    expect(host.querySelector<HTMLInputElement>('#attached-taste')!.value).toBe('4.5')
    expect(host.querySelector('.choices [aria-pressed="true"]')?.textContent?.trim()).toBe('一般')
    expect(host.querySelector('.review-step img')).not.toBeNull()
    expect(uploadImage).toHaveBeenCalledTimes(2)
    expect(cancelImage).not.toHaveBeenCalled()
    expect(submitNewRestaurant).not.toHaveBeenCalled()
    vi.mocked(submitNewRestaurant).mockResolvedValue({ id: '71', status: 'PENDING' })
    await submit()
    expect(submitNewRestaurant).toHaveBeenCalledWith(
      expect.objectContaining({
        address: '校园路一号',
        tags: ['好吃', '实惠'],
        photoIds: ['101'],
        review: expect.objectContaining({
          scores: expect.objectContaining({ taste: 9 }),
          photoIds: ['202'],
        }),
      }),
      'correction-user',
    )
  })
  it('店铺资料无效或图片上传失败时不能进入点评页，不产生申请', async () => {
    await mount('/restaurants/submit?keyword=尚未定位的店铺')
    await clickButton('下一步，写点评 →')
    expect(host.querySelector('.review-step')).toBeNull()
    expect(host.textContent).toContain('请通过高德搜索并选择有效门店')
    vi.mocked(uploadImage).mockRejectedValue(new Error('上传失败'))
    await choosePhoto()
    await submit()
    expect(host.querySelector('.review-step')).toBeNull()
    expect(host.textContent).toContain('请等待图片上传完成')
    expect(submitNewRestaurant).not.toHaveBeenCalled()
  })
  it('联合提交失败后可返回仅提交店铺，换请求编号并仅取消未保存的点评图', async () => {
    const draft = prepareMerchantSubmission({
      name: '新店',
      address: '校园路',
      latitude: 30.75,
      longitude: 103.93,
    })!
    await mount(`/restaurants/submit?draft=${draft}`)
    await field('美食品类', 'OTHER')
    vi.mocked(uploadImage).mockImplementation(async (_file, _owner, purpose) =>
      purpose === 'REVIEW' ? '202' : '101',
    )
    await choosePhoto()
    await clickButton('下一步，写点评 →')
    await field('说说你的用餐体验', '这次提交决定暂时不保存点评。')
    await clickButton('推荐')
    await chooseReviewPhoto()
    vi.mocked(submitNewRestaurant)
      .mockRejectedValueOnce(new Error('请求未保存'))
      .mockRejectedValueOnce(new Error('店铺请求未保存'))
      .mockResolvedValue({ id: '71', status: 'PENDING' })
    await submit()
    const joint = vi.mocked(submitNewRestaurant).mock.calls[0]![0]
    await clickButton('← 返回店铺资料')
    expect(host.textContent).toContain('不会保存这条点评')
    await clickButton('仅提交店铺')
    const shopOnly = vi.mocked(submitNewRestaurant).mock.calls[1]![0]
    expect(shopOnly).not.toHaveProperty('review')
    expect(shopOnly.clientRequestId).not.toBe(joint.clientRequestId)
    await clickButton('仅提交店铺')
    expect(vi.mocked(submitNewRestaurant).mock.calls[2]![0]).toEqual(shopOnly)
    expect(cancelImage).toHaveBeenCalledExactlyOnceWith('202', 'correction-user')
    expect(host.textContent).not.toContain('附带点评已保存')
  })
  it('首次附带点评的草稿不阻止改为食堂，食堂只提交店铺且不附带点评', async () => {
    const draft = prepareMerchantSubmission({
      name: '新食堂',
      address: '校园路',
      latitude: 30.75,
      longitude: 103.93,
    })!
    await mount(`/restaurants/submit?draft=${draft}`)
    await field('美食品类', 'OTHER')
    await clickButton('下一步，写点评 →')
    await field('说说你的用餐体验', '这里应该评价具体的窗口。')
    vi.mocked(uploadImage).mockRejectedValueOnce(new Error('点评图上传失败'))
    await chooseReviewPhoto()
    await clickButton('← 返回店铺资料')
    await field('餐厅类型', 'CANTEEN')
    expect(host.querySelector('.submission-steps')).toBeNull()
    expect(
      [...host.querySelectorAll<HTMLButtonElement>('button')].find(
        (b) => b.textContent?.trim() === '提交审核',
      )!.disabled,
    ).toBe(false)
    vi.mocked(submitNewRestaurant).mockResolvedValue({ id: '71', status: 'PENDING' })
    await submit()
    const payload = vi.mocked(submitNewRestaurant).mock.calls[0]![0]
    expect(payload.restaurantType).toBe('CANTEEN')
    expect(payload).not.toHaveProperty('review')
    expect(host.textContent).not.toContain('附带点评已保存')
  })
  function signIn() {
    useLoginStore().acceptSession({
      accessToken: 'correction-user',
      tokenType: 'Bearer',
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id: 42, username: '本人', roles: ['ROLE_USER'] },
    })
    vi.mocked(fetchMyRequestPhoto).mockResolvedValue(new Blob(['shop'], { type: 'image/jpeg' }))
    vi.mocked(fetchMyRequestReviewPhoto).mockResolvedValue(
      new Blob(['review'], { type: 'image/jpeg' }),
    )
    vi.mocked(uploadImage).mockImplementation(async (_file, _token, purpose) =>
      purpose === 'REVIEW' ? '202' : '101',
    )
  }
  it('编辑本人申请保留点评与原图，使用PUT且不发送提交UUID或删除原绑定', async () => {
    signIn()
    vi.mocked(fetchMyRequestDetail).mockResolvedValue(requestFixture())
    vi.mocked(editMyRequest).mockResolvedValue({ id: '71', status: 'PENDING' })
    await mount('/account/contributions/71/edit')
    expect(host.querySelector('.review-step')).toBeNull()
    expect(host.querySelector<HTMLOptionElement>('option[value="CANTEEN"]')!.disabled).toBe(true)
    expect([...host.querySelectorAll('button')].some((b) => b.textContent === '仅提交店铺')).toBe(
      false,
    )
    await clickButton('下一步，写点评 →')
    expect(editMyRequest).not.toHaveBeenCalled()
    expect(host.querySelector('[role="switch"]')).toBeNull()
    expect(host.querySelector<HTMLInputElement>('#attached-taste')!.value).toBe('3.75')
    expect(host.querySelectorAll('img')).toHaveLength(2)
    await field('说说你的用餐体验', '修改后的点评内容，保留两类原图。')
    await submit()
    const payload = vi.mocked(editMyRequest).mock.calls[0]![1]
    expect(payload).not.toHaveProperty('clientRequestId')
    expect(payload).toMatchObject({
      photoIds: ['101'],
      review: { scores: { taste: 7.5 }, recommendation: 'NEUTRAL', photoIds: ['202'] },
    })
    expect(submitNewRestaurant).not.toHaveBeenCalled()
    expect(cancelImage).not.toHaveBeenCalled()
  })
  it('驳回重提保留原内容及重试编号，并显示新申请回执', async () => {
    signIn()
    vi.mocked(fetchMyRequestDetail).mockResolvedValue(requestFixture('REJECTED'))
    vi.mocked(resubmitMyRequest)
      .mockRejectedValueOnce(new Error('暂时失败'))
      .mockResolvedValue({ id: '72', status: 'PENDING' })
    await mount('/account/contributions/71/edit')
    expect(host.textContent).toContain('位置不准确')
    await clickButton('下一步，写点评 →')
    await submit()
    await submit()
    expect(resubmitMyRequest).toHaveBeenCalledTimes(2)
    expect(vi.mocked(resubmitMyRequest).mock.calls[0]).toEqual(
      vi.mocked(resubmitMyRequest).mock.calls[1],
    )
    expect(await captureLinkTarget(app, host.querySelector('.receipt-detail'))).toBe(
      '/account/contributions?requestId=72',
    )
    expect(editMyRequest).not.toHaveBeenCalled()
  })
  it('已重提的祖先申请不展示可编辑表单，不读取旧点评图', async () => {
    signIn()
    vi.mocked(fetchMyRequestDetail).mockResolvedValue({
      ...requestFixture('REJECTED'),
      resubmittedRequestId: '72',
    })
    await mount('/account/contributions/71/edit')
    expect(host.querySelector('form')).toBeNull()
    expect(host.textContent).toContain('申请状态已变化')
    expect(fetchMyRequestReviewPhoto).not.toHaveBeenCalled()
    expect(uploadImage).not.toHaveBeenCalled()
  })
  it('管理员新建复用高德搜索，选择门店后直接创建而非提交审核', async () => {
    vi.mocked(createRestaurant).mockResolvedValue({ restaurant: responseFixture, deleted: false })
    const router = await mount('/admin/restaurants/new')
    await field('商户名称', '搜索测试店')
    ;[...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === '搜索门店')!.click()
    await settle()
    ;[...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === '选择这家')!.click()
    await settle()
    await field('美食品类', 'OTHER')
    await field('餐厅类型', 'CANTEEN')
    await field('支持堂食', 'true')
    await field('支持外卖', 'false')
    expect(host.querySelector('input[inputmode="decimal"]')).toBeNull()
    expect(host.querySelector('input[aria-label="选择商户照片"]')).not.toBeNull()
    await submit()
    expect(createRestaurant).toHaveBeenCalledWith(
      expect.objectContaining({
        name: '高德分店',
        address: '新的商户地址',
        latitude: 30.675789,
        longitude: 104.099671,
        locationConfirmed: true,
        restaurantType: 'CANTEEN',
        categoryCode: null,
        supportsDineIn: true,
        supportsDelivery: false,
      }),
      'correction-user',
    )
    expect(submitNewRestaurant).not.toHaveBeenCalled()
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/admin/restaurants'))
  })
  it('管理员编辑从授权快照回填，直接保存不创建纠错申请', async () => {
    vi.mocked(fetchRestaurantManagement).mockResolvedValue({
      restaurant: { ...responseFixture, latitude: 30.75, longitude: 103.93 },
      deleted: false,
    })
    vi.mocked(updateRestaurant).mockResolvedValue({ restaurant: responseFixture, deleted: false })
    const router = await mount('/admin/restaurants/19/edit')
    expect(fetchCorrectionSource).not.toHaveBeenCalled()
    expect(host.textContent).not.toContain('我已核对并确认商户位置')
    await submit()
    expect(updateRestaurant).toHaveBeenCalledWith(
      '19',
      expect.objectContaining({ name: responseFixture.name }),
      'correction-user',
    )
    expect(submitRestaurantCorrection).not.toHaveBeenCalled()
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/admin/restaurants'))
  })
  it('食堂回显无分类，切换为普通餐厅后必须重新选择分类', async () => {
    vi.mocked(fetchCorrectionSource).mockResolvedValue({
      ...responseFixture,
      restaurantType: 'CANTEEN',
      categoryCode: null,
      categoryName: null,
      latitude: 30.75,
      longitude: 103.93,
    })
    await mount()
    expect(
      [...host.querySelectorAll('label')].some((l) => l.textContent?.includes('美食品类')),
    ).toBe(false)
    await field('餐厅类型', 'REGULAR')
    await field('我已核对', true)
    await submit()
    expect(submitRestaurantCorrection).not.toHaveBeenCalled()
    expect(host.textContent).toContain('请选择可用分类')
    await field('餐厅类型', 'CANTEEN')
    await submit()
    expect(submitRestaurantCorrection).toHaveBeenCalledWith(
      '19',
      expect.objectContaining({
        restaurantType: 'CANTEEN',
        categoryCode: null,
      }),
      'correction-user',
    )
  })

  it('选图预览、失败重试后随申请提交图片ID，成功离开不删除已绑定文件', async () => {
    await mount()
    vi.mocked(uploadImage).mockRejectedValueOnce(new Error('存储暂不可用')).mockResolvedValue('101')
    await choosePhoto()
    expect(host.querySelector('img[alt="待审商户图片 1"]')).not.toBeNull()
    expect(uploadImage).toHaveBeenCalledWith(expect.any(File), 'correction-user', 'RESTAURANT')
    expect(host.textContent).toContain('存储暂不可用')
    await submit()
    expect(submitRestaurantCorrection).not.toHaveBeenCalled()
    ;[...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === '重试上传')!.click()
    await settle()
    await field('我已核对', true)
    await submit()
    expect(submitRestaurantCorrection).toHaveBeenCalledWith(
      '19',
      expect.objectContaining({ photoIds: ['101'] }),
      'correction-user',
    )
    expect(cancelImage).not.toHaveBeenCalled()
    expect(URL.revokeObjectURL).toHaveBeenCalled()
  })
  it('纠错回显已公开照片，旧图不占新增名额也不随photoIds重复提交或清理', async () => {
    vi.mocked(fetchRestaurantAlbum).mockResolvedValueOnce({
      records: [{ id: '801', position: 0, cover: true, url: '/api/restaurants/19/photos/801' }],
      nextCursor: null,
    })
    await mount()
    expect(fetchRestaurantAlbum).toHaveBeenCalledWith('19', null, expect.any(AbortSignal))
    expect(host.querySelector('.existing-merchant-photos')?.textContent).toContain('当前商户照片')
    expect(host.querySelector('.existing-merchant-photos img')).not.toBeNull()
    expect(host.querySelector('.existing-merchant-photos [aria-label^="移除"]')).toBeNull()
    expect(uploadImage).not.toHaveBeenCalled()
    expect(cancelImage).not.toHaveBeenCalled()
    await choosePhoto()
    await field('我已核对', true)
    await submit()
    expect(submitRestaurantCorrection).toHaveBeenCalledWith(
      '19',
      expect.objectContaining({ photoIds: ['101'] }),
      'correction-user',
    )
    expect(cancelImage).not.toHaveBeenCalled()
  })
  it('只看旧照片并提交文字不上传或取消图片，读取相册失败不阻止资料提交', async () => {
    vi.mocked(fetchRestaurantAlbum).mockRejectedValueOnce(new Error('相册服务暂不可用'))
    await mount()
    expect(host.querySelector('.existing-merchant-photos')?.textContent).toContain(
      '相册服务暂不可用',
    )
    await field('我已核对', true)
    await submit()
    expect(submitRestaurantCorrection).toHaveBeenCalledOnce()
    expect(vi.mocked(submitRestaurantCorrection).mock.calls[0]![1]).not.toHaveProperty('photoIds')
    expect(uploadImage).not.toHaveBeenCalled()
    expect(cancelImage).not.toHaveBeenCalled()
  })
  it('新店投稿不读取不存在的已有商户相册', async () => {
    await mount('/restaurants/submit?keyword=新店')
    expect(host.querySelector('.existing-merchant-photos')).toBeNull()
    expect(fetchRestaurantAlbum).not.toHaveBeenCalled()
  })
  it('移除和离开时清理未提交图片，上传晚到结果也会被取消', async () => {
    await mount()
    let complete!: (id: string) => void
    vi.mocked(uploadImage).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          complete = resolve
        }),
    )
    await choosePhoto()
    host.querySelector<HTMLButtonElement>('[aria-label="移除第1张图片"]')!.click()
    complete('102')
    await settle()
    expect(cancelImage).toHaveBeenCalledWith('102', 'correction-user')
    await choosePhoto()
    app.unmount()
    expect(cancelImage).toHaveBeenCalledWith('101', 'correction-user')
  })
  it('新店投稿带入搜索词，匿名登录回跳仍保留关键词', async () => {
    clearAccessToken()
    await mount('/restaurants/submit?keyword=新店关键词')
    expect(host.querySelector('h1')?.textContent).toBe('提交店铺')
    expect(await captureLinkTarget(app, host.querySelector('.notice a'))).toContain(
      'restaurants/submit?keyword=',
    )
    expect(fetchCorrectionSource).not.toHaveBeenCalled()
  })
  it('选中的门店带入地址与位置，只调用新店待审接口，失败重试沿用编号', async () => {
    const draft = prepareMerchantSubmission({
      name: '新门店',
      address: '新门店地址',
      latitude: 30.675789,
      longitude: 104.099671,
    })!
    const router = await mount(
      `/restaurants/submit?keyword=搜索词&draft=${draft}`,
      '/account/contributions',
    )
    expect(fetchCorrectionSource).not.toHaveBeenCalled()
    expect(host.querySelector('input')?.value).toBe('新门店')
    expect(host.textContent).toContain('已选择：新门店')
    await field('美食品类', 'OTHER')
    vi.mocked(submitNewRestaurant)
      .mockRejectedValueOnce(new Error('网络失败'))
      .mockResolvedValue({ id: '61', status: 'PENDING' })
    await clickButton('仅提交店铺')
    expect(host.querySelector('input')?.value).toBe('新门店')
    const input = vi.mocked(submitNewRestaurant).mock.calls[0]![0]
    expect(input).toMatchObject({
      name: '新门店',
      address: '新门店地址',
      latitude: 30.675789,
      longitude: 104.099671,
      locationConfirmed: true,
    })
    await clickButton('仅提交店铺')
    expect(vi.mocked(submitNewRestaurant).mock.calls[1]![0].clientRequestId).toBe(
      input.clientRequestId,
    )
    expect(submitRestaurantCorrection).not.toHaveBeenCalled()
    expect(host.querySelectorAll('h1')).toHaveLength(1)
    expect(host.querySelector('h1')?.textContent).toBe('已提交，等待审核')
    expect(document.activeElement).toBe(host.querySelector('h1'))
    expect(host.querySelector('.receipt-name')?.textContent).toBe('新门店')
    expect(await captureLinkTarget(app, host.querySelector('.receipt-detail'))).toBe(
      '/account/contributions?requestId=61',
    )
    host.querySelector<HTMLAnchorElement>('.receipt-done')!.click()
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/account/contributions'))
    expect(document.querySelector('.leave-confirmation[open]')).toBeNull()
  })
  it('草稿刷新丢失时只带搜索词，未选位置不能提交', async () => {
    await mount('/restaurants/submit?keyword=新店关键词&draft=expired')
    expect(host.querySelector('input')?.value).toBe('新店关键词')
    expect(fetchCorrectionSource).not.toHaveBeenCalled()
    expect(host.querySelector<HTMLButtonElement>('.address-map')?.disabled).toBe(true)
    await field('美食品类', 'OTHER')
    await submit()
    expect(submitNewRestaurant).not.toHaveBeenCalled()
    expect(host.textContent).toContain('请通过高德搜索并选择有效门店')
  })
  it('从地址核对位置时，地图失败返回仍保留表单和待提交图片', async () => {
    vi.mocked(loadAMap).mockRejectedValue(new Error('offline'))
    const draft = prepareMerchantSubmission({
      name: '新门店',
      address: '新门店地址',
      latitude: 30.675789,
      longitude: 104.099671,
    })!
    const router = await mount(`/restaurants/submit?draft=${draft}`)
    await field('人均消费', '42')
    await choosePhoto()
    const opener = host.querySelector<HTMLButtonElement>('.address-map')!
    const photo = host.querySelector('.merchant-photo-preview img')
    expect(photo).not.toBeNull()
    expect(opener.disabled).toBe(false)
    opener.click()
    await settle()
    const preview = document.querySelector('.restaurant-location-preview[open]')!
    expect(preview.querySelector('[role=alert]')?.textContent).toContain('地图暂时无法加载')
    expect(preview.textContent).toContain('新门店地址')
    preview.dispatchEvent(new Event('cancel', { cancelable: true }))
    await settle()
    expect(router.currentRoute.value.path).toBe('/restaurants/submit')
    expect(document.querySelector('.restaurant-location-preview')).toBeNull()
    expect(host.querySelector<HTMLInputElement>('input[inputmode=numeric]')?.value).toBe('42')
    expect(host.querySelector('.merchant-photo-preview img')).toBe(photo)
    expect(document.activeElement).toBe(opener)
    expect(submitNewRestaurant).not.toHaveBeenCalled()
    expect(cancelImage).not.toHaveBeenCalled()
  })
  it('匿名访问提供登录返回路径，不读取或提交申请', async () => {
    clearAccessToken()
    await mount()
    expect(host.querySelector('form')).toBeNull()
    expect(await captureLinkTarget(app, host.querySelector('.notice a'))).toContain(
      'redirect=/restaurants/19/correction',
    )
    expect(fetchCorrectionSource).not.toHaveBeenCalled()
  })
  it('预填公开资料并允许修改全部业务资料，成功仅显示待审回执', async () => {
    await mount()
    expect(host.querySelector('input')?.value).toBe('原店名')
    expect(host.querySelectorAll('input[type="search"]')).toHaveLength(1)
    expect(host.textContent).not.toContain('校区归属')
    expect(host.querySelector('input[inputmode="decimal"]')).toBeNull()
    expect(searchAMapPlaces).not.toHaveBeenCalled()
    ;[...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === '搜索门店')!.click()
    await settle()
    ;[...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === '选择这家')!.click()
    await settle()
    expect(host.textContent).toContain('已选择：高德分店')
    await field('商户名称', '更正后店名')
    await field('美食品类', 'OTHER')
    await field('餐厅类型', 'CANTEEN')
    await field('人均消费', '42')
    await field('支持堂食', 'true')
    await field('支持外卖', 'false')
    await field('地址', '新的商户地址')
    await field('经营状态', 'CLOSED')
    await field('每行一个标签', '学生聚餐\n价格实惠')
    await submit()
    expect(submitRestaurantCorrection).toHaveBeenCalledOnce()
    expect(submitRestaurantCorrection).toHaveBeenCalledWith(
      '19',
      expect.objectContaining({
        clientRequestId: expect.any(String),
        name: '更正后店名',
        categoryCode: null,
        restaurantType: 'CANTEEN',
        priceKind: 'EXACT',
        pricePerPerson: 42,
        address: '新的商户地址',
        businessStatus: 'CLOSED',
        supportsDineIn: true,
        supportsDelivery: false,
        latitude: 30.675789,
        longitude: 104.099671,
        locationConfirmed: true,
        tags: ['学生聚餐', '价格实惠'],
      }),
      'correction-user',
    )
    expect(host.textContent).toContain('已提交，等待审核')
    expect(host.querySelector('form')).toBeNull()
  })
  it('未确认位置不可提交，失败保留资料及同次请求编号，换账号后阻止提交', async () => {
    await mount()
    await submit()
    expect(submitRestaurantCorrection).not.toHaveBeenCalled()
    await field('我已核对', true)
    vi.mocked(submitRestaurantCorrection).mockRejectedValue(new Error('服务失败'))
    await submit()
    const first = vi.mocked(submitRestaurantCorrection).mock.calls[0]![1].clientRequestId
    expect(host.querySelector('input')?.value).toBe('原店名')
    await submit()
    expect(vi.mocked(submitRestaurantCorrection).mock.calls[1]![1].clientRequestId).toBe(first)
    setAccessToken('different-user')
    await settle()
    expect(host.querySelector('form')).toBeNull()
    expect(host.textContent).toContain('登录账号已变化')
  })
})
