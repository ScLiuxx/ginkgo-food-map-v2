const LONG_ID_PATTERN = /^[1-9]\d{0,18}$/
const MAX_LONG_ID = 9223372036854775807n

/**
 * 校验后端 Long 身份。
 *
 * 后端以字符串传输Long，避免 JavaScript 53 位精度丢失；
 * 这里同时拒绝前导零、负数、超出 Long 上限和带小数的值。
 */
export function isLongId(value: unknown): value is string {
  return (
    typeof value === 'string' && LONG_ID_PATTERN.test(value) && BigInt(value) <= MAX_LONG_ID
  )
}

/** RFC 4122 v4 形状的请求号；只用于幂等去重，不承担安全随机职责。 */
export function createRequestId(): string {
  const hex = '0123456789abcdef'
  let result = ''
  for (let index = 0; index < 36; index++) {
    if (index === 8 || index === 13 || index === 18 || index === 23) {
      result += '-'
    } else if (index === 14) {
      result += '4'
    } else if (index === 19) {
      result += hex[8 + Math.floor(Math.random() * 4)]
    } else {
      result += hex[Math.floor(Math.random() * 16)]
    }
  }
  return result
}
