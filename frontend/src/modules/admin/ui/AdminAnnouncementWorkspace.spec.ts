import { createApp, nextTick, type App } from 'vue'
import { createPinia, disposePinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useLoginStore } from '@/modules/auth'
import { clearAccessToken } from '@/shared/api/authSession'
import { mockDialog } from '@/shared/test/mockDialog'
import AdminAnnouncementWorkspace from './AdminAnnouncementWorkspace.vue'

vi.mock('../api/restaurantRequestsApi', () => ({
  fetchRequestPage: vi.fn().mockResolvedValue({ counts: { PENDING: 0 } }),
}))

describe('公告编辑离开保护', () => {
  let app: App, host: HTMLDivElement, restore: () => void
  let pinia: ReturnType<typeof createPinia>
  beforeEach(() => {
    restore = mockDialog()
    pinia = createPinia()
    setActivePinia(pinia)
    useLoginStore().acceptSession({
      accessToken: 'admin-token',
      tokenType: 'Bearer',
      expiresAt: '2099-01-01T00:00:00Z',
      user: { id: 1, username: '管理员', roles: ['ROLE_ADMIN'] },
    })
    host = document.createElement('div')
    document.body.append(host)
  })
  afterEach(() => {
    app?.unmount()
    host.remove()
    disposePinia(pinia)
    clearAccessToken()
    restore()
  })
  it('未发布公告取消离开保留正文，确认后离开', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: '/admin/announcements',
          component: AdminAnnouncementWorkspace,
          props: { username: '管理员', loggingOut: false, sessionError: null },
        },
        { path: '/admin/reviews', component: { template: '<div />' } },
      ],
    })
    await router.push('/admin/announcements')
    app = createApp(RouterView).use(router).use(pinia)
    app.mount(host)
    const input = host.querySelector('textarea')!
    input.value = '尚未发布的正文'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    const cancel = router.push('/admin/reviews')
    const dialog = () => document.querySelector<HTMLDialogElement>('.leave-confirmation')!
    await vi.waitFor(() => expect(dialog().open).toBe(true))
    dialog().querySelector<HTMLButtonElement>('button')!.click()
    await cancel
    expect(router.currentRoute.value.path).toBe('/admin/announcements')
    expect(input.value).toBe('尚未发布的正文')
    const leave = router.push('/admin/reviews')
    await vi.waitFor(() => expect(dialog().open).toBe(true))
    dialog().querySelector<HTMLButtonElement>('.leave')!.click()
    await leave
    expect(router.currentRoute.value.path).toBe('/admin/reviews')
  })
})
