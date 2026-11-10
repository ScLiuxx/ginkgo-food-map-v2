import { request } from '../../shared/api/request'
import {
  toMyReviewPageView,
  toReviewPageView,
  type MyReviewPageRaw,
  type MyReviewPageView,
  type ReviewGroupPageRaw,
  type ReviewPageView,
  type ReviewSort,
} from './types'

/**
 * 读取一页作者组点评。
 *
 * 公开读取不携带令牌，避免过期登录阻止游客查看；
 * 响应经 toReviewPageView 校验后再交给页面，异常响应不能伪装成空列表。
 */
export async function fetchReviewGroups(
  restaurantId: string,
  page: number,
  sort: ReviewSort,
): Promise<ReviewPageView> {
  const raw = await request<ReviewGroupPageRaw>({
    path: `/api/restaurants/${encodeURIComponent(restaurantId)}/reviews?page=${page}&pageSize=8&sort=${sort}`,
    auth: 'none',
  })
  return toReviewPageView(raw, page)
}

/** 独立窗口使用同一评价格式，但路径与对象编号属于 canteen-windows。 */
export async function fetchWindowReviewGroups(
  windowId: string,
  page: number,
  sort: ReviewSort,
): Promise<ReviewPageView> {
  const raw = await request<ReviewGroupPageRaw>({
    path: `/api/canteen-windows/${encodeURIComponent(windowId)}/reviews?page=${page}&pageSize=8&sort=${sort}`,
    auth: 'none',
  })
  return toReviewPageView(raw, page)
}

/**
 * 读取本人点评（按店铺分组）。
 *
 * 作者只由服务端当前身份确定；不能通过参数查看其他用户。
 * 私有数据必须携带登录令牌，异常响应由校验层拒绝而不是降级成空列表。
 */
export async function fetchMyReviewGroups(page: number): Promise<MyReviewPageView> {
  const raw = await request<MyReviewPageRaw>({
    path: `/api/me/reviews?page=${page}&pageSize=8`,
    auth: 'required',
  })
  return toMyReviewPageView(raw, page)
}

/**
 * 读取其他用户的公开点评（仍按店铺分组）。
 *
 * 公开主页要求登录；响应中的作者必须回指请求用户，
 * 避免错误路由或缓存把其他人的点评显示在当前主页。
 */
export async function fetchUserReviewGroups(
  userId: string,
  page: number,
): Promise<MyReviewPageView> {
  const raw = await request<MyReviewPageRaw>({
    path: `/api/users/${encodeURIComponent(userId)}/reviews?page=${page}&pageSize=8`,
    auth: 'required',
  })
  if (!raw || !raw.author || raw.author.id !== userId) {
    throw new Error('该用户的点评无法确认，请重新加载。')
  }
  return toMyReviewPageView(raw, page)
}
