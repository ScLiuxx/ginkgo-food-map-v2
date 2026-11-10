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
