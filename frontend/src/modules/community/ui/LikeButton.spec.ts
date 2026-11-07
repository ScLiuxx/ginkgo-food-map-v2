import { createApp, h, ref, nextTick, type App } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'
import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest'
import LikeButton from './LikeButton.vue'
import { fetchLike, setLike } from '../api/likesApi'
import { setAccessToken, clearAccessToken } from '@/shared/api/authSession'
vi.mock('../api/likesApi', () => ({ fetchLike: vi.fn(), setLike: vi.fn() }))
const state = (liked = false) => ({ targetId: '1', count: liked ? 1 : 0, liked, available: true })
const settle = async () => {
  for (let i = 0; i < 12; i++) await nextTick()
}
/** 真实组件验证重复点击抑制、响应不确定时先读、换号隔离和自赞入口限制。 */
describe('点赞按钮', () => {
  let app: App | undefined, host: HTMLDivElement
  const viewer = ref('42')
  beforeEach(() => {
    vi.resetAllMocks()
    setAccessToken('token')
    viewer.value = '42'
    host = document.createElement('div')
    document.body.append(host)
    vi.mocked(fetchLike).mockResolvedValue(state())
  })
  afterEach(() => {
    app?.unmount()
    app = undefined
    host.remove()
    clearAccessToken()
  })
  async function mount(own = false) {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }],
    })
    await router.push('/restaurants/1')
    app = createApp({
      render: () =>
        h(LikeButton, {
          id: '1',
          type: 'REVIEW',
          own,
          authenticated: true,
          viewerId: viewer.value,
        }),
    })
    app.use(router)
    app.mount(host)
    await settle()
  }
  it('成功前禁用重复提交，成功显示真实计数', async () => {
    let resolve!: (x: ReturnType<typeof state>) => void
    vi.mocked(setLike).mockReturnValue(
      new Promise((done) => {
        resolve = done
      }),
    )
    await mount()
    const button = host.querySelector<HTMLButtonElement>('.like-entry')!
    button.click()
    await settle()
    expect(button.disabled).toBe(true)
    button.click()
    expect(setLike).toHaveBeenCalledTimes(1)
    resolve(state(true))
    await settle()
    expect(button.textContent).toContain('已赞')
    expect(button.textContent).toContain('1')
  })
  it('失败后保留原计数，重新读取确认状态后才允许操作', async () => {
    vi.mocked(setLike).mockRejectedValue(new Error('响应丢失'))
    await mount()
    host.querySelector<HTMLButtonElement>('.like-entry')!.click()
    await settle()
    expect(host.textContent).toContain('响应丢失')
    expect(host.querySelector<HTMLButtonElement>('.like-entry')!.disabled).toBe(true)
    vi.mocked(fetchLike).mockResolvedValue(state(true))
    host.querySelector<HTMLButtonElement>('.like-error button')!.click()
    await settle()
    expect(host.textContent).toContain('已赞')
    expect(setLike).toHaveBeenCalledTimes(1)
  })
  it('换号后旧写入回执不能恢复已赞状态', async () => {
    let resolve!: (x: ReturnType<typeof state>) => void
    vi.mocked(setLike).mockReturnValue(
      new Promise((done) => {
        resolve = done
      }),
    )
    await mount()
    host.querySelector<HTMLButtonElement>('.like-entry')!.click()
    await settle()
    setAccessToken('second')
    viewer.value = '88'
    await settle()
    resolve(state(true))
    await settle()
    expect(host.querySelector('.like-entry')?.getAttribute('aria-pressed')).toBe('false')
  })
  it('自己的内容仍显示计数但禁止点击', async () => {
    await mount(true)
    expect(host.querySelector<HTMLButtonElement>('.like-entry')!.disabled).toBe(true)
    expect(setLike).not.toHaveBeenCalled()
  })
})
