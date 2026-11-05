import { createApp, nextTick, type App } from 'vue'
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
import { fetchRestaurantAlbum, fetchRestaurantAlbumImage } from '../api/restaurantAlbumApi'

vi.mock('../api/restaurantAlbumApi', () => ({
  fetchRestaurantAlbum: vi.fn(),
  fetchRestaurantAlbumImage: vi.fn(),
}))

vi.mock('@/modules/file/api/mediaApi', () => ({ uploadImage: vi.fn(), cancelImage: vi.fn() }))
vi.mock('@/modules/file/model/photos', () => ({ compressPhoto: vi.fn() }))

vi.mock('@/modules/map/sdk/places', () => ({ searchAMapPlaces: vi.fn() }))

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
  const settle = async () => {
    for (let i = 0; i < 10; i++) await nextTick()
  }
  beforeEach(() => {
    vi.resetAllMocks()
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
    host.remove()
    clearAccessToken()
    vi.unstubAllGlobals()
  })
  async function mount(path = '/restaurants/19/correction') {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
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
    await router.push(path)
    app = createApp(RouterView).use(router)
    app.mount(host)
    await settle()
    return router
  }
  async function field(name: string, value: string | boolean) {
    const label = [...host.querySelectorAll('label')].find((l) =>
      l.textContent?.trim().startsWith(name),
    )!
    const el = label.querySelector<HTMLInputElement>('input,select,textarea')!
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
  async function choosePhoto() {
    const input = host.querySelector<HTMLInputElement>('input[type="file"]')!
    Object.defineProperty(input, 'files', {
      configurable: true,
      value: [new File(['jpeg'], 'merchant.jpg', { type: 'image/jpeg' })],
    })
    input.dispatchEvent(new Event('change', { bubbles: true }))
    await settle()
  }
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
    expect(host.querySelector('input[type="file"]')).toBeNull()
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
    await field('我已核对', true)
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
    expect(host.querySelector('a[href^="/login"]')?.getAttribute('href')).toContain(
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
    await mount(`/restaurants/submit?keyword=搜索词&draft=${draft}`)
    expect(fetchCorrectionSource).not.toHaveBeenCalled()
    expect(host.querySelector('input')?.value).toBe('新门店')
    expect(host.textContent).toContain('已选择：新门店')
    await field('美食品类', 'OTHER')
    vi.mocked(submitNewRestaurant)
      .mockRejectedValueOnce(new Error('网络失败'))
      .mockResolvedValue({ id: '61', status: 'PENDING' })
    await submit()
    expect(host.querySelector('input')?.value).toBe('新门店')
    const input = vi.mocked(submitNewRestaurant).mock.calls[0]![0]
    expect(input).toMatchObject({
      name: '新门店',
      address: '新门店地址',
      latitude: 30.675789,
      longitude: 104.099671,
      locationConfirmed: true,
    })
    await submit()
    expect(vi.mocked(submitNewRestaurant).mock.calls[1]![0].clientRequestId).toBe(
      input.clientRequestId,
    )
    expect(submitRestaurantCorrection).not.toHaveBeenCalled()
    expect(host.textContent).toContain('已提交，等待管理员审核')
  })
  it('草稿刷新丢失时只带搜索词，未选位置不能提交', async () => {
    await mount('/restaurants/submit?keyword=新店关键词&draft=expired')
    expect(host.querySelector('input')?.value).toBe('新店关键词')
    expect(fetchCorrectionSource).not.toHaveBeenCalled()
    await field('美食品类', 'OTHER')
    await submit()
    expect(submitNewRestaurant).not.toHaveBeenCalled()
    expect(host.textContent).toContain('请通过高德搜索并选择有效门店')
  })
  it('匿名访问提供登录返回路径，不读取或提交申请', async () => {
    clearAccessToken()
    await mount()
    expect(host.querySelector('form')).toBeNull()
    expect(host.querySelector('a[href^="/login"]')?.getAttribute('href')).toContain(
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
    await field('人均类型', 'EXACT')
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
    expect(host.textContent).toContain('已提交，等待管理员审核')
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
