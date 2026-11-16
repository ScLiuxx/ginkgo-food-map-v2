import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import LinkExistingRestaurantDialog from './LinkExistingRestaurantDialog.vue'
import { searchExistingRestaurants, type ExistingRestaurant } from '../api/restaurantRequestsApi'

vi.mock('../api/restaurantRequestsApi', () => ({ searchExistingRestaurants: vi.fn() }))
const shop = (id: string): ExistingRestaurant => ({
  id,
  name: `餐厅${id}`,
  restaurantType: 'REGULAR',
  address: '地址',
  latitude: 30,
  longitude: 104,
  businessStatus: 'OPERATING',
})

/** 核对窗口以真实DOM交互验证选择、查询竞争和确认载荷。 */
describe('已收录店铺选择', () => {
  let app: ReturnType<typeof createApp>, host: HTMLElement
  const confirm = vi.fn(),
    close = vi.fn()
  const flush = async () => {
    for (let i = 0; i < 8; i++) await nextTick()
  }
  beforeEach(() => {
    vi.useFakeTimers()
    vi.resetAllMocks()
    HTMLDialogElement.prototype.showModal = vi.fn()
    HTMLDialogElement.prototype.close = vi.fn()
    host = document.createElement('div')
    document.body.append(host)
  })
  afterEach(() => {
    app?.unmount()
    host.remove()
    vi.useRealTimers()
  })
  async function mount() {
    app = createApp(LinkExistingRestaurantDialog, {
      restaurantName: '原店名',
      restaurantType: 'REGULAR',
      submitting: false,
      error: '',
      onConfirm: confirm,
      onClose: close,
    })
    app.mount(host)
    await flush()
  }
  it('不默认选择，核对并点击确认后只输出目标ID', async () => {
    vi.mocked(searchExistingRestaurants).mockResolvedValue({
      records: [shop('991')],
      totalPages: 1,
    })
    await mount()
    const button = host.querySelector<HTMLButtonElement>('.confirm')!
    expect(button.disabled).toBe(true)
    host.querySelector<HTMLInputElement>('input[type="radio"]')!.click()
    await flush()
    expect(button.disabled).toBe(false)
    button.click()
    expect(confirm).toHaveBeenCalledWith('991')
    expect(searchExistingRestaurants).toHaveBeenCalledWith(
      '原店名',
      'REGULAR',
      1,
      expect.any(AbortSignal),
    )
  })
  it('改关键词立即清空选择，旧请求迟到不能替换新结果', async () => {
    let oldResolve!: (value: { records: ExistingRestaurant[]; totalPages: number }) => void
    vi.mocked(searchExistingRestaurants).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          oldResolve = resolve
        }),
    )
    await mount()
    const input = host.querySelector<HTMLInputElement>('input[type="search"]')!
    vi.mocked(searchExistingRestaurants).mockResolvedValue({
      records: [shop('992')],
      totalPages: 1,
    })
    input.value = '新关键词'
    input.dispatchEvent(new Event('input'))
    await flush()
    await vi.advanceTimersByTimeAsync(250)
    await flush()
    oldResolve({ records: [shop('991')], totalPages: 1 })
    await flush()
    expect(host.textContent).toContain('餐厅992')
    expect(host.textContent).not.toContain('餐厅991')
    host.querySelector<HTMLInputElement>('input[type="radio"]')!.click()
    await flush()
    input.value = ''
    input.dispatchEvent(new Event('input'))
    await flush()
    expect(host.querySelector<HTMLButtonElement>('.confirm')!.disabled).toBe(true)
  })
})
