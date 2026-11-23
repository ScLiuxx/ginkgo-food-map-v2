import { expect, vi } from 'vitest'

/** 资源打开只检查浏览器调用，不实际访问地图或图片服务。 */
export function captureResourceTarget(element: Element | null): string {
  if (!(element instanceof HTMLElement)) throw new Error('Missing resource link')
  const open = vi.spyOn(globalThis, 'open').mockReturnValue(null)
  try {
    expect(element.hasAttribute('href')).toBe(false)
    element.click()
    expect(open).toHaveBeenCalledOnce()
    expect(open).toHaveBeenCalledWith(expect.any(String), '_blank', 'noopener,noreferrer')
    return String(open.mock.calls[0]![0])
  } finally {
    open.mockRestore()
  }
}
