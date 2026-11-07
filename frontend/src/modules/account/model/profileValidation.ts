/** 与服务端UserProfilePolicy保持相同的基础识别范围；只规范化检查副本，不改写正常Unicode简介。 */
export function normalizeBio(value: string): string {
  return value.replace(/\r\n?/g, '\n').replace(/^[\s\u0085]+|[\s\u0085]+$/gu, '')
}
const link =
  /[a-z][a-z0-9+.-]*:\/\/|www\.|(?:mailto|tel|weixin|mqq|qq):|(?:[a-z0-9\p{Script=Han}](?:[a-z0-9\p{Script=Han}-]*[a-z0-9\p{Script=Han}])?\.)+(?:[a-z]{2,24}|中国|公司|网络)(?![a-z0-9-])|(?<![0-9])(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?![0-9])/u
const mobile = /(?<![0-9])(?:\+?86)?1[3-9][0-9]{9}(?![0-9])/u
const contact =
  /(?:微信(?:号|号码)?|wechat|wx|vx|v信|加v|加微)[:=]?([a-z][a-z0-9_-]{5,19}|[0-9]{6,20})|(?<![a-z0-9])wxid_[a-z0-9_-]{6,}|(?:qq(?:号码|群号|号|群)?|企鹅号?)[:=]?[1-9][0-9]{4,11}(?![0-9])|(?:手机(?:号)?|电话|tel|联系方式)[:=]?[+0-9][0-9()-]{5,}/u

/** 字数按Unicode码点计算，允许空简介；前端提示不能代替服务端校验。 */
export function bioValidationError(value: string): string {
  const text = normalizeBio(value)
  if (Array.from(text).length > 100) return '个人简介最多100字'
  for (const character of text) {
    const point = character.codePointAt(0)!
    if (
      ((point <= 0x1f || (point >= 0x7f && point <= 0x9f)) && point !== 10 && point !== 9) ||
      (point >= 0xd800 && point <= 0xdfff)
    )
      return '个人简介包含不支持的字符'
  }
  const probe = text
    .normalize('NFKC')
    .replace(/[\s\u0085\p{Cf}]+/gu, '')
    .toLowerCase()
  const phone = probe.replace(/(?<=[0-9])[._()-](?=[0-9])/gu, '')
  return link.test(probe) || mobile.test(phone) || contact.test(probe)
    ? '个人简介不能包含微信、QQ、手机号或网址'
    : ''
}
