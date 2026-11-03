/** 只在当前页面内存传递用户选中的高德门店，地址/位置不写URL或浏览器持久存储。 */
export interface MerchantSubmissionPlace {
  name: string
  address: string
  longitude: number
  latitude: number
}
let pending: { id: string; place: MerchantSubmissionPlace } | undefined
/** 每次点击入口替换当前草稿，返回随机引用供登录回跳；不传门店时清除旧选择。 */
export function prepareMerchantSubmission(
  place?: MerchantSubmissionPlace,
): string | undefined {
  pending = place
    ? { id: globalThis.crypto.randomUUID(), place: { ...place } }
    : undefined
  return pending?.id
}
/** 登录回跳保留内存中的草稿；页面刷新后需要重新选店确认位置。 */
export function readMerchantSubmission(
  id: string,
): MerchantSubmissionPlace | undefined {
  return pending?.id === id ? pending.place : undefined
}
