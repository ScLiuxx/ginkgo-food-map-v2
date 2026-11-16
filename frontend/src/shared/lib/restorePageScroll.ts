/** 返回列表或详情时等待异步内容就绪，再恢复位置；超时或再次跳转时停止等待。 */
export function waitForPageLayout(
  isCurrent: () => boolean,
  selector = '.account-center',
): Promise<boolean> {
  const deadline = performance.now() + 5000
  return new Promise((resolve) => {
    function check() {
      if (!isCurrent()) return resolve(false)
      const page = document.querySelector(selector)
      if (
        (page &&
          !page.matches('[aria-busy="true"]') &&
          !page.querySelector('[aria-busy="true"]')) ||
        performance.now() >= deadline
      )
        return resolve(true)
      requestAnimationFrame(check)
    }
    requestAnimationFrame(check)
  })
}
