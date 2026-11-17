import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MockInstance } from 'vitest'

const storageKey = 'foodmap.access-token'

/** 每次重新加载模块模拟页面关闭后重新打开；只使用虚构凭证，不访问后端。 */
describe('persistent authentication session', () => {
  let listeners: MockInstance<typeof window.addEventListener>

  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    vi.resetModules()
    listeners = vi.spyOn(window, 'addEventListener')
  })

  afterEach(() => {
    for (const [event, listener] of listeners.mock.calls) {
      if (event === 'storage') window.removeEventListener(event, listener)
    }
    vi.restoreAllMocks()
    localStorage.clear()
    sessionStorage.clear()
  })

  it('restores a persisted JWT after the page module is reloaded', async () => {
    const firstPage = await import('./authSession')
    firstPage.setAccessToken('saved-session')
    expect(localStorage.getItem(storageKey)).toBe('saved-session')
    expect(sessionStorage.getItem(storageKey)).toBeNull()
    vi.resetModules()
    const reopenedPage = await import('./authSession')
    expect(reopenedPage.getAccessToken()).toBe('saved-session')
  })

  it('migrates the legacy tab token once and removes it on logout', async () => {
    sessionStorage.setItem(storageKey, 'legacy-session')
    const session = await import('./authSession')
    expect(session.getAccessToken()).toBe('legacy-session')
    expect(localStorage.getItem(storageKey)).toBe('legacy-session')
    expect(sessionStorage.getItem(storageKey)).toBeNull()
    session.clearAccessToken('legacy-session')
    expect(localStorage.getItem(storageKey)).toBeNull()
    vi.resetModules()
    expect((await import('./authSession')).getAccessToken()).toBeNull()
  })

  it('prefers the shared persistent token over an old tab token', async () => {
    localStorage.setItem(storageKey, 'latest-session')
    sessionStorage.setItem(storageKey, 'stale-session')
    const session = await import('./authSession')
    expect(session.getAccessToken()).toBe('latest-session')
    expect(sessionStorage.getItem(storageKey)).toBeNull()
  })

  it('keeps a newer login when a different tab changes storage before an old 401 arrives', async () => {
    const session = await import('./authSession')
    session.setAccessToken('old-session')
    localStorage.setItem(storageKey, 'new-session')
    session.clearAccessToken('old-session')
    expect(session.getAccessToken()).toBe('new-session')
    expect(localStorage.getItem(storageKey)).toBe('new-session')
    session.clearAccessToken('old-session')
    expect(session.getAccessToken()).toBe('new-session')
  })

  it('synchronizes login and logout from other tabs and ignores late event values', async () => {
    const session = await import('./authSession')
    localStorage.setItem(storageKey, 'other-tab-session')
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: storageKey,
        newValue: 'stale-event-value',
        storageArea: localStorage,
      }),
    )
    expect(session.getAccessToken()).toBe('other-tab-session')
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: storageKey,
        newValue: null,
        storageArea: sessionStorage,
      }),
    )
    expect(session.getAccessToken()).toBe('other-tab-session')
    localStorage.clear()
    window.dispatchEvent(new StorageEvent('storage', { key: null, storageArea: localStorage }))
    expect(session.getAccessToken()).toBeNull()
  })

  it('uses memory when the browser refuses storage access', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    const session = await import('./authSession')
    expect(session.getAccessToken()).toBeNull()
    session.setAccessToken('memory-session')
    expect(session.getAccessToken()).toBe('memory-session')
    session.clearAccessToken('memory-session')
    expect(session.getAccessToken()).toBeNull()
  })
})
