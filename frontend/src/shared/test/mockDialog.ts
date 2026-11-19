/** JSDOM尚未实现原生dialog行为；真实焦点与模态层由浏览器验证。 */
export function mockDialog() {
  const prototype = globalThis.HTMLDialogElement.prototype
  const methods = ['showModal', 'close'] as const
  const previous = methods.map((method) => Object.getOwnPropertyDescriptor(prototype, method))
  for (const method of methods) {
    Object.defineProperty(prototype, method, {
      configurable: true,
      value(this: globalThis.HTMLDialogElement) {
        this.open = method === 'showModal'
        if (method === 'close')
          globalThis.queueMicrotask(() => this.dispatchEvent(new Event('close')))
      },
    })
  }
  return () => {
    methods.forEach((method, index) => {
      const descriptor = previous[index]
      if (descriptor) Object.defineProperty(prototype, method, descriptor)
      else Reflect.deleteProperty(prototype, method)
    })
  }
}
