import { request } from '../../shared/api/request'
import { toReviewPageView, type ReviewGroupPageRaw, type ReviewPageView, type ReviewSort } from './types'

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
