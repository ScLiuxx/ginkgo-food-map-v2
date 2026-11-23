import { createApp, nextTick, type App } from 'vue'
import { createPinia, disposePinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useLoginStore } from '@/modules/auth'
import { clearAccessToken } from '@/shared/api/authSession'
import { httpClient } from '@/shared/api/httpClient'
import { uploadImage, cancelImage } from '@/modules/file/api/mediaApi'
import { prepareAvatar, avatarVersions } from '../model/avatar'
import AvatarSetting from './AvatarSetting.vue'

vi.mock('@/modules/file/api/mediaApi', () => ({ uploadImage: vi.fn(), cancelImage: vi.fn() }))
vi.mock('../model/avatar', async (original) => ({
  ...(await original<typeof import('../model/avatar')>()),
  prepareAvatar: vi.fn(),
}))
let app: App, host: HTMLDivElement, pinia: ReturnType<typeof createPinia>
const dialogMethods = ['showModal', 'close'] as const
const dialogDescriptors = dialogMethods.map((method) =>
  Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, method),
)
const cropped = new File(['jpeg'], 'avatar.jpg', { type: 'image/jpeg' })
const settle = () =>
  vi.waitFor(() => expect(host.querySelector('button.avatar-save')?.textContent).toBe('保存'))
function signIn(id = 11) {
  useLoginStore().acceptSession({
    accessToken: `session-${id}`,
    tokenType: 'Bearer',
    expiresAt: '2099-01-01T00:00:00Z',
    user: { id, username: '测试同学', roles: ['ROLE_USER'] },
  })
}
beforeEach(async () => {
  vi.resetAllMocks()
  clearAccessToken()
  pinia = createPinia()
  setActivePinia(pinia)
  signIn()
  vi.mocked(prepareAvatar).mockResolvedValue(cropped)
  vi.mocked(uploadImage).mockResolvedValue('501')
  vi.mocked(cancelImage).mockResolvedValue()
  vi.spyOn(httpClient, 'put').mockResolvedValue({ status: 204 })
  vi.stubGlobal(
    'URL',
    class extends URL {
      static createObjectURL = vi.fn(() => 'blob:avatar-preview')
      static revokeObjectURL = vi.fn()
    },
  )
  Object.defineProperties(HTMLDialogElement.prototype, {
    showModal: {
      configurable: true,
      value(this: HTMLDialogElement) {
        this.open = true
      },
    },
    close: {
      configurable: true,
      value(this: HTMLDialogElement) {
        this.open = false
      },
    },
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:path(.*)*', component: AvatarSetting }],
  })
  await router.push('/account/settings')
  await router.isReady()
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(AvatarSetting).use(pinia).use(router)
  app.mount(host)
})
afterEach(() => {
  app?.unmount()
  host?.remove()
  disposePinia(pinia)
  clearAccessToken()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  dialogMethods.forEach((method, index) => {
    const descriptor = dialogDescriptors[index]
    if (descriptor) Object.defineProperty(HTMLDialogElement.prototype, method, descriptor)
    else Reflect.deleteProperty(HTMLDialogElement.prototype, method)
  })
})
async function choose() {
  const input = host.querySelector('input')!
  Object.defineProperty(input, 'files', {
    configurable: true,
    value: [new File(['original'], 'test.png', { type: 'image/png' })],
  })
  input.dispatchEvent(new Event('change', { bubbles: true }))
  await vi.waitFor(() =>
    expect(host.querySelector('.avatar-preview img')?.getAttribute('src')).toBe(
      'blob:avatar-preview',
    ),
  )
  await settle()
}
function open() {
  host.querySelector<HTMLButtonElement>('.avatar-row')!.click()
}
function save() {
  host.querySelector<HTMLButtonElement>('.avatar-save')!.click()
}

it('选图和取消仅释放本地预览，只有保存才上传头像用途并更新所有展示版本', async () => {
  open()
  await choose()
  expect(uploadImage).not.toHaveBeenCalled()
  const previous = avatarVersions['11'] ?? 0
  save()
  await vi.waitFor(() => expect(host.textContent).toContain('头像已更新'))
  expect(uploadImage).toHaveBeenCalledExactlyOnceWith(cropped, 'session-11', 'AVATAR')
  expect(httpClient.put).toHaveBeenCalledWith(
    '/api/me/avatar',
    { fileId: '501' },
    { headers: { Authorization: 'Bearer session-11' } },
  )
  expect(avatarVersions['11']).toBe(previous + 1)
  expect(cancelImage).not.toHaveBeenCalled()
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:avatar-preview')
})

it('保存回执失败保留同一fileId重试，不重复上传，上传中禁止关闭', async () => {
  vi.mocked(httpClient.put).mockRejectedValueOnce(new Error('网络中断'))
  open()
  await choose()
  save()
  await vi.waitFor(() => expect(host.textContent).toContain('网络中断'))
  expect(host.querySelector<HTMLDialogElement>('dialog')!.open).toBe(true)
  save()
  await vi.waitFor(() => expect(host.textContent).toContain('头像已更新'))
  expect(uploadImage).toHaveBeenCalledTimes(1)
  expect(httpClient.put).toHaveBeenCalledTimes(2)
})

it('上传失败保留预览，可重新上传，取消不删除原头像', async () => {
  vi.mocked(uploadImage).mockRejectedValueOnce(new Error('存储不可用'))
  open()
  await choose()
  save()
  await vi.waitFor(() => expect(host.textContent).toContain('存储不可用'))
  expect(host.querySelector('.avatar-preview img')).not.toBeNull()
  expect(httpClient.put).not.toHaveBeenCalled()
  host.querySelector<HTMLButtonElement>('header button')!.click()
  expect(cancelImage).not.toHaveBeenCalled()
  expect(host.querySelector<HTMLDialogElement>('dialog')!.open).toBe(false)
})

it('绑定失败后取消仅清理本次临时上传，失败清理交给TTL且不影响关闭', async () => {
  vi.mocked(httpClient.put).mockRejectedValueOnce(new Error('网络中断'))
  vi.mocked(cancelImage).mockRejectedValueOnce(new Error('清理稍后重试'))
  open()
  await choose()
  save()
  await vi.waitFor(() => expect(host.textContent).toContain('网络中断'))
  host.querySelector<HTMLButtonElement>('header button')!.click()
  expect(cancelImage).toHaveBeenCalledWith('501', 'session-11')
  expect(host.querySelector<HTMLDialogElement>('dialog')!.open).toBe(false)
})

it('切换账号隔离迟到上传结果，清理仍用原账号且绝不绑定给新账号', async () => {
  let resolve!: (id: string) => void
  vi.mocked(uploadImage).mockReturnValueOnce(
    new Promise((done) => {
      resolve = done
    }),
  )
  open()
  await choose()
  save()
  await nextTick()
  expect(host.querySelector<HTMLButtonElement>('header button')!.disabled).toBe(true)
  signIn(22)
  resolve('501')
  await vi.waitFor(() => expect(cancelImage).toHaveBeenCalledWith('501', 'session-11'))
  expect(httpClient.put).not.toHaveBeenCalled()
  expect(host.textContent).not.toContain('头像已更新')
})

it('重新选图清理上次失败上传；非法图片保留当前有效预览', async () => {
  vi.mocked(httpClient.put).mockRejectedValueOnce(new Error('网络中断'))
  open()
  await choose()
  save()
  await vi.waitFor(() => expect(host.textContent).toContain('网络中断'))
  await choose()
  expect(cancelImage).toHaveBeenCalledWith('501', 'session-11')
  vi.mocked(prepareAvatar).mockRejectedValueOnce(new Error('图片无法读取'))
  const input = host.querySelector('input')!
  input.dispatchEvent(new Event('change', { bubbles: true }))
  await vi.waitFor(() => expect(host.textContent).toContain('图片无法读取'))
  expect(host.querySelector('.avatar-preview img')).not.toBeNull()
})
