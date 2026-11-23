import type { App } from 'vue'
import type { Router } from 'vue-router'
import { expect, vi } from 'vitest'

/** 检查真实点击提交给路由的目标并取消此次导航，避免读取目的页业务数据。 */
export async function captureLinkTarget(
  app: App | undefined,
  element: Element | null | undefined,
): Promise<string> {
  if (!app || !(element instanceof HTMLElement)) throw new Error('Missing mounted app or link')
  const router: Router = app.config.globalProperties.$router
  let destination: string | undefined
  const stop = router.beforeEach((to) => {
    destination = to.fullPath
    return false
  })
  // 当前页链接属于重复导航，不进入beforeEach，但仍会触发afterEach。
  const stopAfter = router.afterEach((to) => {
    destination ??= to.fullPath
  })
  try {
    expect(element.hasAttribute('href')).toBe(false)
    element.click()
    await vi.waitFor(() => expect(destination).toBeDefined())
    return destination!
  } finally {
    stop()
    stopAfter()
  }
}
