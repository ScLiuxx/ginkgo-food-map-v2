import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import LinkExistingRestaurantDialog from './LinkExistingRestaurantDialog.vue'
import { searchExistingRestaurants, type ExistingRestaurant } from '../api/restaurantRequestsApi'

vi.mock('../api/restaurantRequestsApi', () => ({ searchExistingRestaurants: vi.fn() }))
vi.mock('@/modules/restaurant', () => ({
  RestaurantManagementPreview: {
    props: ['id'],
    template: '<div data-testid="preview">{{ id }}</div>',
  },
}))
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
    HTMLDialogElement.prototype.showModal = vi.fn(function (this: HTMLDialogElement) {
      this.open = true
    })
    HTMLDialogElement.prototype.close = vi.fn(function (this: HTMLDialogElement) {
      this.open = false
    })
    host = document.createElement('div')
    document.body.append(host)
  })
  afterEach(() => {
    app?.unmount()
    host.remove()
    vi.useRealTimers()
  })
  async function mount(submitting = false) {
    app = createApp(LinkExistingRestaurantDialog, {
      restaurantName: '原店名',
      restaurantType: 'REGULAR',
      submitting,
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
    host.querySelector<HTMLButtonElement>('.candidate-select')!.click()
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
  it('原弹窗查看详情并返回，保留搜索和选择并恢复查看按钮焦点', async () => {
    vi.mocked(searchExistingRestaurants).mockResolvedValue({
      records: [shop('991'), shop('992')],
      totalPages: 1,
    })
    await mount()
    const views = host.querySelectorAll<HTMLButtonElement>('.candidate-view')
    expect(host.querySelector('.candidate a')).toBeNull()
    const input = host.querySelector('input')!
    views[1]!.click()
    await flush()
    expect(host.querySelector('[data-testid=preview]')?.textContent).toBe('992')
    expect(host.querySelector<HTMLButtonElement>('.confirm')!.disabled).toBe(true)
    host.querySelector<HTMLButtonElement>('[aria-label="返回店铺列表"]')!.click()
    await flush()
    expect(host.querySelector('[data-testid=preview]')).toBeNull()
    expect(document.activeElement).toBe(views[1])
    host.querySelector<HTMLButtonElement>('.candidate-select')!.click()
    await flush()
    views[1]!.click()
    await flush()
    host.querySelector<HTMLButtonElement>('[aria-label="关闭店铺详情"]')!.click()
    await flush()
    expect(host.querySelector('input')).toBe(input)
    expect(input.value).toBe('原店名')
    expect(searchExistingRestaurants).toHaveBeenCalledTimes(1)
    expect(host.querySelectorAll('.chosen')).toHaveLength(1)
    expect(host.querySelector('.chosen')?.textContent).toContain('餐厅991')
    host.querySelector<HTMLButtonElement>('.confirm')!.click()
    expect(confirm).toHaveBeenCalledWith('991')
  })
  it('详情中的Esc先返回列表，列表中的Esc才关闭选店弹窗', async () => {
    vi.mocked(searchExistingRestaurants).mockResolvedValue({
      records: [shop('991')],
      totalPages: 1,
    })
    await mount()
    host.querySelector<HTMLButtonElement>('.candidate-view')!.click()
    await flush()
    const dialog = host.querySelector('dialog')!
    const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
    host.querySelector('[aria-label="返回店铺列表"]')!.dispatchEvent(escape)
    await flush()
    expect(escape.defaultPrevented).toBe(true)
    expect(close).not.toHaveBeenCalled()
    expect(host.querySelector('[data-testid=preview]')).toBeNull()
    dialog.dispatchEvent(new Event('cancel', { cancelable: true }))
    expect(close).toHaveBeenCalledTimes(1)
  })
  it('提交期间不能打开详情或通过Esc关闭选店弹窗', async () => {
    vi.mocked(searchExistingRestaurants).mockResolvedValue({
      records: [shop('991')],
      totalPages: 1,
    })
    await mount(true)
    const view = host.querySelector<HTMLButtonElement>('.candidate-view')!
    expect(view.disabled).toBe(true)
    view.click()
    host.querySelector('dialog')!.dispatchEvent(new Event('cancel', { cancelable: true }))
    await flush()
    expect(host.querySelector('[data-testid=preview]')).toBeNull()
    expect(close).not.toHaveBeenCalled()
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
    host.querySelector<HTMLButtonElement>('.candidate-select')!.click()
    await flush()
    input.value = ''
    input.dispatchEvent(new Event('input'))
    await flush()
    expect(host.querySelector<HTMLButtonElement>('.confirm')!.disabled).toBe(true)
  })
})
