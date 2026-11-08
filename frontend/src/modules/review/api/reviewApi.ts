import { httpClient } from '@/shared/api/httpClient'
import { getAccessToken } from '@/shared/api/authSession'
import type { ApiResponse } from '@/shared/api/api.types'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import type {
  ReviewSort,
  ReviewDraft,
  ReviewGateway,
  ReviewPublishReceipt,
  ReviewPage,
  ReviewHistoryPage,
  ReviewItem,
  Scores,
  ReplyPage,
  ReplyReceipt,
} from '../model/review'
import { uploadImage, cancelImage } from '@/modules/file/api/mediaApi'

/** 固定提交开始时的凭证，避免请求排队期间账号变化而以新账号发布旧草稿。 */
export async function publishTextReview(draft: ReviewDraft): Promise<ReviewPublishReceipt> {
  if (
    draft.photos.length &&
    (draft.photoIds?.length !== draft.photos.length ||
      new Set(draft.photoIds).size !== draft.photos.length)
  )
    throw new Error('请等待所有照片上传完成或移除照片后提交。')
  const token = getAccessToken()
  if (!token) throw new Error('请先登录后再发布点评。')
  try {
    const response = await httpClient.post<ApiResponse<ReviewPublishReceipt>>(
      `/api/${draft.windowId ? 'canteen-windows' : 'restaurants'}/${encodeURIComponent(draft.windowId ?? draft.restaurantId)}/reviews`,
      {
        clientRequestId: draft.clientRequestId,
        scores: { ...draft.scores },
        recommended: draft.recommended,
        body: draft.body,
        ...(draft.photoIds?.length ? { photoIds: [...draft.photoIds] } : {}),
      },
      { headers: { Authorization: `Bearer ${token}` } },
    )
    const receipt = response.data.data
    if (
      !response.data.success ||
      !receipt ||
      typeof receipt.reviewId !== 'string' ||
      !/^[1-9]\d*$/.test(receipt.reviewId) ||
      (draft.windowId
        ? receipt.windowId !== draft.windowId || receipt.restaurantId !== null
        : receipt.restaurantId !== draft.restaurantId || !!receipt.windowId) ||
      typeof receipt.replayed !== 'boolean'
    ) {
      throw new Error('发布结果无法确认，请保留当前内容重试，系统会校验同次提交编号。')
    }
    return receipt
  } catch (reason) {
    throw new Error(
      `${toErrorMessage(reason)}。正文已保留；超时可能已经保存，同内容重试不会重复发布。`,
      { cause: reason },
    )
  }
}

/** 后端主卡协议；历史不内嵌，管理权限不能从公开作者ID推断。 */
interface GroupPageDto extends Omit<ReviewPage, 'records'> {
  records: {
    author: { id: string; username: string; level?: number }
    historyCount: number
    nextHistoryCursor?: string | null
    currentReview: {
      id: string
      scores: Scores
      overallScore: number
      recommended: boolean
      body: string
      createdAt: string
      scoringStatus: 'current' | 'history'
      photos: { id: string; position: number; url: string }[]
    }
  }[]
}

/** 匿名读取一页作者组，透传取消信号；失败不回退到示例或伪造空列表。 */
export async function fetchReviewPage(
  restaurantId: string,
  signal: AbortSignal,
  page = 1,
  sort: ReviewSort = 'DEFAULT',
  window = false,
): Promise<ReviewPage> {
  try {
    const response = await httpClient.get<ApiResponse<GroupPageDto>>(
      `/api/${window ? 'canteen-windows' : 'restaurants'}/${encodeURIComponent(restaurantId)}/reviews`,
      { params: { page, pageSize: 8, sort }, signal },
    )
    const data = response.data.data
    if (
      !response.data.success ||
      !data ||
      !Array.isArray(data.records) ||
      data.page !== page ||
      data.pageSize !== 8 ||
      data.records.length > 8 ||
      ![data.totalGroups, data.totalReviews, data.totalPages].every(
        (n) => Number.isSafeInteger(n) && n >= 0,
      ) ||
      data.totalReviews < data.totalGroups ||
      data.totalPages !== Math.ceil(data.totalGroups / 8)
    ) {
      throw new Error('点评响应格式错误，请重试。')
    }
    return {
      ...data,
      records: data.records.map((group) => {
        const review = group.currentReview
        if (
          !group.author ||
          typeof group.author.id !== 'string' ||
          typeof group.author.username !== 'string' ||
          !review ||
          typeof review.id !== 'string' ||
          review.scoringStatus !== 'current' ||
          typeof review.body !== 'string' ||
          typeof review.recommended !== 'boolean' ||
          !Number.isFinite(Date.parse(review.createdAt)) ||
          !Number.isFinite(review.overallScore) ||
          !review.scores ||
          !['taste', 'value', 'service', 'ambience'].every((key) => {
            const value = review.scores[key as keyof Scores]
            return typeof value === 'number' && value >= 0 && value <= 10
          }) ||
          !Number.isSafeInteger(group.historyCount) ||
          group.historyCount < 0 ||
          !Array.isArray(review.photos) ||
          review.photos.length > 5 ||
          !review.photos.every(
            (photo, i) =>
              typeof photo.id === 'string' &&
              photo.position === i &&
              photo.url === `/api/reviews/${review.id}/photos/${i}`,
          )
        ) {
          throw new Error('点评响应格式错误，请重试。')
        }
        return {
          id: review.id,
          authorId: group.author.id,
          username: group.author.username,
          authorLevel: group.author.level,
          historyCount: group.historyCount,
          nextHistoryCursor: group.nextHistoryCursor,
          scoringStatus: 'current',
          overallScore: review.overallScore,
          scores: review.scores,
          createdAt: review.createdAt,
          recommended: review.recommended,
          body: review.body,
          photos: review.photos.map((photo) => ({
            id: photo.id,
            url: photo.url,
          })),
          canDelete: false,
          replies: [],
        }
      }),
    }
  } catch (reason) {
    if (signal.aborted) throw reason
    throw new Error(toErrorMessage(reason), { cause: reason })
  }
}

/** 历史响应独立校验，公开作者资料不能推导管理权限。 */
export async function fetchReviewHistory(
  restaurantId: string,
  authorId: string,
  signal: AbortSignal,
  cursor?: string,
  window = false,
): Promise<ReviewHistoryPage> {
  const response = await httpClient.get<
    ApiResponse<{
      author: { id: string; username: string; level?: number } | null
      currentReviewId: string | null
      records: GroupPageDto['records'][number]['currentReview'][]
      hasMore: boolean
      nextCursor: string | null
    }>
  >(
    `/api/${window ? 'canteen-windows' : 'restaurants'}/${encodeURIComponent(restaurantId)}/reviews/authors/${encodeURIComponent(authorId)}/history`,
    { params: { pageSize: 5, ...(cursor ? { cursor } : {}) }, signal },
  )
  const data = response.data.data
  const invalid = () => new Error('历史点评响应格式错误，请刷新点评。')
  if (
    !response.data.success ||
    !data ||
    !Array.isArray(data.records) ||
    data.records.length > 5 ||
    typeof data.hasMore !== 'boolean' ||
    (data.currentReviewId !== null &&
      (typeof data.currentReviewId !== 'string' || !/^[1-9]\d*$/.test(data.currentReviewId))) ||
    (data.hasMore
      ? typeof data.nextCursor !== 'string' ||
        !/^[A-Za-z0-9_-]{1,200}$/.test(data.nextCursor) ||
        !data.records.length
      : data.nextCursor !== null) ||
    (data.currentReviewId !== null &&
      (!data.author || data.author.id !== authorId || typeof data.author.username !== 'string')) ||
    (data.currentReviewId === null && data.records.length)
  )
    throw invalid()
  const ids = new Set<string>()
  const records: ReviewItem[] = data.records.map((r) => {
    if (
      !r ||
      typeof r.id !== 'string' ||
      !/^[1-9]\d*$/.test(r.id) ||
      ids.has(r.id) ||
      r.id === data.currentReviewId ||
      r.scoringStatus !== 'history' ||
      typeof r.body !== 'string' ||
      typeof r.recommended !== 'boolean' ||
      !Number.isFinite(Date.parse(r.createdAt)) ||
      !Number.isFinite(r.overallScore) ||
      !r.scores ||
      !['taste', 'value', 'service', 'ambience'].every(
        (k) =>
          typeof r.scores[k as keyof Scores] === 'number' &&
          r.scores[k as keyof Scores] >= 0 &&
          r.scores[k as keyof Scores] <= 10,
      ) ||
      !Array.isArray(r.photos) ||
      r.photos.length > 5 ||
      !r.photos.every(
        (p, i) =>
          typeof p.id === 'string' &&
          p.position === i &&
          p.url === `/api/reviews/${r.id}/photos/${i}`,
      )
    )
      throw invalid()
    ids.add(r.id)
    return {
      ...r,
      authorId,
      username: data.author!.username,
      authorLevel: data.author!.level,
      photos: r.photos.map((p) => ({ id: p.id, url: p.url })),
      canDelete: false,
      replies: [],
    }
  })
  return {
    records,
    currentReviewId: data.currentReviewId,
    hasMore: data.hasMore,
    nextCursor: data.nextCursor,
  }
}

/** 回复单段校验后才交给界面；异常响应不能伪装成空列表。 */
export async function fetchReplies(
  reviewId: string,
  signal: AbortSignal,
  cursor?: string,
): Promise<ReplyPage> {
  const response = await httpClient.get<ApiResponse<ReplyPage>>(
    `/api/reviews/${encodeURIComponent(reviewId)}/replies`,
    { params: { pageSize: 10, ...(cursor ? { cursor } : {}) }, signal },
  )
  const data = response.data.data
  const id = (value: unknown) => typeof value === 'string' && /^[1-9]\d*$/.test(value)
  if (
    !response.data.success ||
    !data ||
    !Array.isArray(data.records) ||
    data.records.length > 10 ||
    typeof data.hasMore !== 'boolean' ||
    (data.hasMore
      ? typeof data.nextCursor !== 'string' ||
        !/^[A-Za-z0-9_-]{1,200}$/.test(data.nextCursor) ||
        !data.records.length ||
        data.nextCursor === cursor
      : data.nextCursor !== null) ||
    !data.records.every(
      (row) =>
        row &&
        id(row.id) &&
        row.author &&
        id(row.author.id) &&
        typeof row.author.username === 'string' &&
        typeof row.body === 'string' &&
        typeof row.createdAt === 'string' &&
        Number.isFinite(Date.parse(row.createdAt)),
    ) ||
    new Set(data.records.map((row) => row.id)).size !== data.records.length
  ) {
    throw new Error('回复响应格式错误，请重新加载。')
  }
  return data
}

/** 固定发起时凭证；失败保留原请求号，只有校验过回执才能确认发送成功。 */
export async function publishReply(
  reviewId: string,
  body: string,
  clientRequestId: string,
  signal?: AbortSignal,
): Promise<ReplyReceipt> {
  const token = getAccessToken()
  if (!token) throw new Error('请先登录后再回复。')
  try {
    const response = await httpClient.post<ApiResponse<ReplyReceipt>>(
      `/api/reviews/${encodeURIComponent(reviewId)}/replies`,
      { body, clientRequestId },
      { headers: { Authorization: `Bearer ${token}` }, signal },
    )
    const receipt = response.data.data
    if (
      !response.data.success ||
      !receipt ||
      receipt.reviewId !== reviewId ||
      typeof receipt.replyId !== 'string' ||
      !/^[1-9]\d*$/.test(receipt.replyId) ||
      typeof receipt.replayed !== 'boolean'
    )
      throw new Error('回复结果无法确认')
    return receipt
  } catch (reason) {
    throw new Error(
      `${toErrorMessage(reason)}。文字已保留；超时可能已经保存，同内容重试不会重复回复。`,
      { cause: reason },
    )
  }
}

/** 固定开始时凭证；真实归属由服务端校验，不发送可伪造的作者ID。 */
export async function deleteReview(reviewId: string): Promise<void> {
  const token = getAccessToken()
  if (!token) throw new Error('请先登录后删除自己的点评。')
  try {
    await httpClient.delete(`/api/reviews/${encodeURIComponent(reviewId)}`, {
      headers: { Authorization: `Bearer ${token}` },
    })
  } catch (reason) {
    throw new Error(`${toErrorMessage(reason)}。删除未确认，可重试或刷新核对。`, { cause: reason })
  }
}

/** 固定操作开始时的凭证，取消请求不代表服务端一定未删除，原作者可以安全重试。 */
export async function deleteReply(replyId: string, signal: AbortSignal): Promise<void> {
  const token = getAccessToken()
  if (!token) throw new Error('请先登录后删除自己的回复。')
  try {
    const response = await httpClient.delete(`/api/review-replies/${encodeURIComponent(replyId)}`, {
      headers: { Authorization: `Bearer ${token}` },
      signal,
    })
    if (response.status !== 204) throw new Error('删除结果无法确认')
  } catch (reason) {
    throw new Error(`${toErrorMessage(reason)}。删除未确认，可重试或刷新核对。`, { cause: reason })
  }
}

/** 正式发布、读取、回复和本人删除；预览传输另行注入。 */
export const textReviewGateway: ReviewGateway = {
  supportsPhotos: true,
  supportsReplies: true,
  uploadPhoto: uploadImage,
  cancelPhoto: cancelImage,
  publish: publishTextReview,
  list: fetchReviewPage,
  history: fetchReviewHistory,
  listReplies: fetchReplies,
  reply: publishReply,
  remove: deleteReview,
  removeReply: deleteReply,
}

/** 窗口读取独立路由；点评本身的回复、照片和删除继续使用全局点评ID。 */
export const windowReviewGateway: ReviewGateway = {
  ...textReviewGateway,
  list: (id, signal, page, sort) => fetchReviewPage(id, signal, page, sort, true),
  history: (id, author, signal, cursor) => fetchReviewHistory(id, author, signal, cursor, true),
}
