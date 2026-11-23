import type { RouteLocationNormalizedLoaded, RouteLocationRaw } from 'vue-router'
import { isReturnDestination } from './useReturnNavigation'

/** 写点评属于当前页面的操作；保留分页、筛选和锚点，避免绕道首页丢失现场。 */
export function reviewDestination(route: RouteLocationNormalizedLoaded): RouteLocationRaw {
  // 认证页本身不承载写评回跳，避免把登录地址再次包装成登录目标。
  if (/^\/(?:login|register|password-reset|admin)(?:\/|$)/.test(route.path))
    return '/?writeReview=1'
  return {
    path: route.path,
    query: { ...route.query, writeReview: '1' },
    hash: route.hash,
  }
}

/** 登录只放行阅读页的写评意图和明确的展示参数，拒绝嵌套重定向与任意额外参数。 */
export function isReviewLoginDestination(value: string): boolean {
  if (!isReturnDestination(value)) return false
  const url = new URL(value, 'https://foodmap.invalid')
  if (
    url.searchParams.getAll('writeReview').length !== 1 ||
    url.searchParams.get('writeReview') !== '1'
  )
    return false
  const displayParameters: Record<string, RegExp> = {
    writeReview: /^1$/,
    page: /^[1-9]\d{0,5}$/,
    pageSize: /^(?:10|20|50)$/,
    status: /^(?:ALL|PENDING|APPROVED|REJECTED|WITHDRAWN|LINKED_EXISTING)$/,
    tab: /^(?:reviews|favorites)$/,
    from: /^(?:map|picker)$/,
    q: /^.{0,200}$/u,
    keyword: /^.{0,200}$/u,
    requestId: /^[1-9]\d{0,18}$/,
  }
  return [...url.searchParams].every(
    ([key, entry]) =>
      url.searchParams.getAll(key).length === 1 &&
      Object.hasOwn(displayParameters, key) &&
      displayParameters[key]!.test(entry),
  )
}

/** 详情自带固定对象的编辑器，其余页面由布局中的全站编辑器处理，避免双弹窗。 */
export function hasDetailComposer(path: string): boolean {
  return /^\/restaurants\/[1-9]\d*$/.test(path)
}
