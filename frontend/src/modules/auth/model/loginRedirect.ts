import { isReviewLoginDestination } from '@/shared/lib/reviewNavigation'

/** 只接受已知站内页面及写评展示参数，拒绝外部URL和任意路径作为登录回跳。 */
export function loginRedirect(value: unknown): string {
  if (typeof value !== 'string') return '/'
  if (isReviewLoginDestination(value)) return value
  const destinations = [
    /^\/\?writeReview=1$/,
    /^\/restaurants\/(?:new|submit|[1-9]\d*(?:\/(?:edit|correction))?)(?:\?writeReview=1)?$/,
    /^\/account(?:\/(?:reviews|favorites|contributions|settings|growth|following|followers))?$/,
    /^\/account\/contributions\/[1-9]\d{0,18}\/edit$/,
    /^\/messages(?:\/(?:likes|comments|followers|system))?$/,
    /^\/users\/[1-9]\d*$/,
  ]
  return destinations.some((rule) => rule.test(value)) ? value : '/'
}
