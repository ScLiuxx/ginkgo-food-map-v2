import { createApp, h, nextTick } from 'vue'
import type { App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it } from 'vitest'
import CanteenWindows from './CanteenWindows.vue'
import type { DiningWindow } from '../model/campusDining'
import { diningDetails } from '../model/campusDining'

/** 仅在测试中构造窗口；生产页面不会注入样例，ID与楼层名称相互独立。 */
const windowFixture = (
  id: string,
  floor: string | null,
  overrides: Partial<DiningWindow> = {},
): DiningWindow => ({
  id,
  canteenId: '100',
  name: `测试窗口${id}`,
  floor,
  score: null,
  reviewCount: 0,
  price: null,
  specialties: [],
  closed: false,
  ...overrides,
})

describe('canteen window browsing', () => {
  let app: App | undefined
  let host: HTMLDivElement
  afterEach(() => {
    app?.unmount()
    host?.remove()
  })
  async function mount(windows: DiningWindow[] | null, query: Record<string, string> = {}) {
    host = document.createElement('div')
    document.body.append(host)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        { path: '/canteen-windows/:id', component: { template: '<div />' } },
      ],
    })
    await router.push({ path: '/', query })
    app = createApp({
      render: () => h(CanteenWindows, { canteenId: '100', windows }),
    }).use(router)
    app.mount(host)
    await nextTick()
    return router
  }
  async function choose(label: string, group: string) {
    const button = [
      ...host.querySelectorAll<HTMLButtonElement>(`[aria-label="${group}"] button`),
    ].find((node) => node.textContent?.trim().startsWith(label))!
    button.click()
    await nextTick()
    return button
  }

  it('filters real windows by floor and links stable IDs without category controls', async () => {
    await mount([
      windowFixture('201', '一楼'),
      windowFixture('202', '二楼'),
      windowFixture('203', '二楼'),
    ])
    expect(host.querySelectorAll('.window-row')).toHaveLength(3)
    expect(host.querySelectorAll('.floor-section')).toHaveLength(2)
    const firstFloor = host.querySelector<HTMLDetailsElement>('.floor-section')!
    expect(firstFloor.open).toBe(true)
    firstFloor.open = false
    expect(firstFloor.querySelectorAll('.window-row')).toHaveLength(1)
    expect(host.querySelector('[aria-label="按楼层筛选窗口"]')?.textContent).not.toContain('三楼')
    const floor = await choose('二楼', '按楼层筛选窗口')
    expect(floor.getAttribute('aria-pressed')).toBe('true')
    expect(host.querySelectorAll('.window-row')).toHaveLength(2)
    expect(host.querySelector('[aria-label="按美食类别筛选窗口"]')).toBeNull()
    expect(host.querySelector('.window-row')?.getAttribute('href')).toContain(
      '/canteen-windows/202?fromFloor=',
    )
    await choose('一楼', '按楼层筛选窗口')
    expect(host.querySelectorAll('.window-row')).toHaveLength(1)
    await choose('全部楼层', '按楼层筛选窗口')
    expect(host.querySelectorAll('.window-row')).toHaveLength(3)
  })

  it('keeps unknown-floor and closed windows, rejects foreign relationships and allows matching parent IDs and preserves real zero', async () => {
    await mount([
      windowFixture('201', null, {
        score: 0,
        reviewCount: 1,
        price: 0,
        closed: true,
      }),
      windowFixture('202', '二楼', { canteenId: '999' }),
      windowFixture('100', '一楼'),
    ])
    expect(host.querySelectorAll('.window-row')).toHaveLength(2)
    const text = host.querySelector('[href*="canteen-windows/201"]')!.textContent!
    expect(text).toContain('0.0')
    expect(text).toContain('¥0')
    expect(text).toContain('暂停营业')
    expect(text).toContain('历史')
    expect(text).toContain('楼层待补充')
  })

  it('distinguishes unavailable data from a known empty list without a user submission entry', async () => {
    await mount(null)
    expect(host.textContent).toContain('窗口资料尚未接入')
    expect(host.textContent).not.toContain('已收录 0')
    expect(host.querySelectorAll('[aria-label="按楼层筛选窗口"] button')).toHaveLength(1)
    expect(host.querySelector('#supplement-window')).toBeNull()
    expect(host.textContent).not.toContain('补充窗口')
    expect(host.querySelectorAll('form')).toHaveLength(0)
    app?.unmount()
    host.remove()
    await mount([])
    expect(host.textContent).toContain('暂未收录窗口')
    expect(host.textContent).toContain('已收录 0')
  })

  it('recognizes only the existing canteen category and does not infer campus or windows from a name', () => {
    expect(diningDetails({ category: '食堂' })).toMatchObject({
      kind: 'canteen',
      campus: null,
      windows: null,
    })
    expect(diningDetails({ category: '其他' })).toMatchObject({
      kind: 'restaurant',
      parent: null,
    })
  })
  it('restores the floor from a legacy URL while ignoring its obsolete category filter', async () => {
    const router = await mount(
      [windowFixture('201', '一楼'), windowFixture('202', '二楼'), windowFixture('203', '二楼')],
      { floor: '二楼', windowCategory: '面食' },
    )
    expect(host.querySelectorAll('.window-row')).toHaveLength(2)
    const link = host.querySelector<HTMLAnchorElement>('.window-row')!
    const target = router.resolve(link.getAttribute('href')!)
    expect(target.path).toBe('/canteen-windows/202')
    expect(target.query).toEqual({ fromFloor: '二楼' })
    const counts = host.querySelector('[aria-label="按楼层筛选窗口"]')!.textContent!
    expect(counts).toContain('全部楼层(3)')
    expect(counts).toContain('二楼(2)')
  })
})
