/** 只接受已知站内页面，拒绝外部URL、额外查询参数和任意路径作为登录回跳。 */
export function loginRedirect(value: unknown): string {
  if (typeof value !== 'string') return '/'
  const destinations = [
    /^\/\?writeReview=1$/,
    /^\/restaurants\/(?:new|submit|[1-9]\d*(?:\/(?:edit|correction))?)(?:\?writeReview=1)?$/,
    /^\/account(?:\/(?:reviews|favorites|contributions|settings|growth|following|followers))?$/,
    /^\/messages(?:\/(?:likes|comments|followers|system))?$/,
    /^\/users\/[1-9]\d*$/,
  ]
  return destinations.some((rule) => rule.test(value)) ? value : '/'
}
