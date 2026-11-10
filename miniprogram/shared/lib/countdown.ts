/**
 * 验证码重发倒计时。
 *
 * 立即回调一次剩余秒数，之后每秒递减；归零时回调 0 并停止。
 * 返回停止函数，页面卸载或重复发送前必须调用，避免旧定时器写回新状态。
 */
export function startCountdown(
  seconds: number,
  onTick: (remaining: number) => void,
): () => void {
  let remaining = seconds
  onTick(remaining)
  const timer = setInterval(() => {
    remaining -= 1
    if (remaining <= 0) {
      clearInterval(timer)
      onTick(0)
      return
    }
    onTick(remaining)
  }, 1000)
  return () => clearInterval(timer)
}
