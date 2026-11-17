import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { fetchSystemStatus } from '../api/systemApi'
import { useSystemStore } from './system.store'

vi.mock('../api/systemApi', () => ({
  fetchSystemStatus: vi.fn(),
}))

describe('system store', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('records a successful backend status response', async () => {
    vi.mocked(fetchSystemStatus).mockResolvedValue({
      application: 'foodmap',
      architecture: 'modular-monolith',
    })

    const store = useSystemStore()
    await store.loadStatus()

    expect(store.online).toBe(true)
    expect(store.status?.application).toBe('foodmap')
    expect(store.error).toBeNull()
  })
})
