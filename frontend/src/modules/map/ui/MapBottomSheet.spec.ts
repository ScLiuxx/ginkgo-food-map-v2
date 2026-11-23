import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MapBottomSheet from './MapBottomSheet.vue'

/** 验证手势归属与停靠状态；真实滚动、地图版权及屏幕几何另由浏览器触屏检查。 */
describe('手机地图底部面板', () => {
  let app: App
  let host: HTMLDivElement
  let list: HTMLElement
  function mountSheet(selected = false, selectionHeight = 190) {
    app = createApp(MapBottomSheet, {
      listId: 'sheet-test-list',
      selectionId: 'sheet-test-selection',
      selected,
      selectionHeight,
      title: '餐厅结果',
    })
    app.mount(host)
  }
  beforeEach(async () => {
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe() {}
        disconnect() {}
      },
    )
    host = document.createElement('div')
    document.body.append(host)
    mountSheet()
    await nextTick()
    list = document.createElement('div')
    list.className = 'map-result-panel'
    host.querySelector('#sheet-test-list')!.append(list)
  })
  afterEach(() => {
    app.unmount()
    host.remove()
    vi.unstubAllGlobals()
  })
  const state = () => host.querySelector<HTMLElement>('.mobile-map-sheet')!.dataset.snap
  async function key(key: string) {
    host
      .querySelector('.mobile-sheet-grip')!
      .dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }))
    await nextTick()
  }
  function touch(target: Element, type: string, x: number, y: number) {
    const event = new Event(type, { bubbles: true, cancelable: true })
    const point = { identifier: 1, clientX: x, clientY: y }
    Object.defineProperties(event, {
      touches: { value: type === 'touchend' || type === 'touchcancel' ? [] : [point] },
      changedTouches: { value: [point] },
    })
    target.dispatchEvent(event)
    return event
  }
  async function drag(target: Element, deltaY: number, deltaX = 0) {
    touch(target, 'touchstart', 100, 300)
    const move = touch(target, 'touchmove', 100 + deltaX, 300 + deltaY)
    touch(target, 'touchend', 100 + deltaX, 300 + deltaY)
    await nextTick()
    return move
  }
  it('上拉依次展开到半屏和全屏，下拉回到收起，取消拖动保留原停靠位', async () => {
    expect(state()).toBe('collapsed')
    expect((await drag(list, -100)).defaultPrevented).toBe(true)
    expect(state()).toBe('half')
    await drag(list, -200)
    expect(state()).toBe('full')
    await drag(list, 240)
    expect(state()).toBe('collapsed')
    touch(list, 'touchstart', 100, 300)
    touch(list, 'touchmove', 100, 100)
    touch(list, 'touchcancel', 100, 100)
    await nextTick()
    expect(state()).toBe('collapsed')
  })
  it('全屏时列表未到顶部的下滑和向上滚动均交给原生滚动', async () => {
    await key('End')
    list.scrollTop = 80
    expect((await drag(list, 120)).defaultPrevented).toBe(false)
    expect(state()).toBe('full')
    expect(list.scrollTop).toBe(80)
    list.scrollTop = 0
    expect((await drag(list, -120)).defaultPrevented).toBe(false)
    expect(state()).toBe('full')
    expect((await drag(list, 180)).defaultPrevented).toBe(true)
    expect(state()).not.toBe('full')
  })
  it('横向滑动不接管，也不误展开面板', async () => {
    expect((await drag(list, 15, 100)).defaultPrevented).toBe(false)
    expect(state()).toBe('collapsed')
  })
  it('触屏的隐式指针捕获释放不会提前取消尚未结束的touch手势', async () => {
    touch(list, 'touchstart', 100, 300)
    touch(list, 'touchmove', 100, 180)
    const lost = new Event('lostpointercapture', { bubbles: true })
    Object.defineProperties(lost, { pointerType: { value: 'touch' }, pointerId: { value: 1 } })
    list.dispatchEvent(lost)
    touch(list, 'touchend', 100, 180)
    await nextTick()
    expect(state()).toBe('half')
  })
  it('拖动后抑制卡片点击，避免抬手误选店铺', async () => {
    const card = document.createElement('button')
    const select = vi.fn()
    card.addEventListener('click', select)
    list.append(card)
    await drag(card, -120)
    card.click()
    expect(select).not.toHaveBeenCalled()
  })
  it('手柄支持键盘全展开、逐级收起及直接收起', async () => {
    await key('End')
    expect(state()).toBe('full')
    await key('ArrowDown')
    expect(state()).toBe('half')
    await key('Home')
    expect(state()).toBe('collapsed')
    await key('ArrowUp')
    expect(state()).toBe('half')
  })
  it('从手柄下拉可收起已滚动的列表，并恢复顶部预览', async () => {
    await key('End')
    list.scrollTop = 120
    await drag(host.querySelector('.mobile-sheet-grip')!, 100)
    expect(state()).toBe('half')
    expect(list.scrollTop).toBe(0)
  })
  it('小屏长选店卡片优先原生滚动，手柄仍可展开列表', async () => {
    app.unmount()
    mountSheet(true, 300)
    await nextTick()
    const content = host.querySelector<HTMLElement>('.mobile-sheet-selection')!
    Object.defineProperties(content, { scrollHeight: { value: 300 }, clientHeight: { value: 100 } })
    expect((await drag(content, -100)).defaultPrevented).toBe(false)
    expect(state()).toBe('collapsed')
    await drag(host.querySelector('.mobile-sheet-grip')!, -100)
    expect(state()).not.toBe('collapsed')
  })
})
