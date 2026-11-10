import { API_BASE_URL } from '../../config/env'
import { clearSession, getToken } from '../../shared/auth/session'
import {
  ApiError,
  asEnvelope,
  messageFrom,
  request,
} from '../../shared/api/request'

/**
 * 点评发布：评分提交、图片压缩上传与幂等请求号。
 *
 * 约束来自后端发布接口：
 * - 四维评分 0–10，步进 0.5；
 * - 正文按去首尾空白后的 Unicode 码点计 5–500；
 * - 最多 5 张本人 REVIEW 用途临时图，单图不超过 300KiB；
 * - 相同 clientRequestId 只在内容完全一致时重放，内容变化必须换新编号。
 */

export const MAX_REVIEW_PHOTOS = 5
export const MAX_REVIEW_BODY = 500
const MIN_REVIEW_BODY = 5
const MAX_UPLOAD_BYTES = 300 * 1024
const QUALITY_STEPS = [80, 62, 45]

export type ScoreKey = 'taste' | 'value' | 'service' | 'ambience'

export interface ReviewScores {
  taste: number
  value: number
  service: number
  ambience: number
}

export interface UploadedPhoto {
  fileId: string
  byteSize: number | null
  width: number | null
  height: number | null
}

/** 发布输入；餐厅与窗口共用同一套评分、正文和照片约束。 */
export interface ReviewPublishInput {
  clientRequestId: string
  scores: ReviewScores
  recommended: boolean
  body: string
  photoIds: string[]
}

/**
 * 发布回执。
 *
 * 餐厅与窗口互斥：餐厅发布返回 restaurantId，窗口发布返回 windowId，
 * 另一个字段为 null。调用方必须按目标类型核对，避免把同号对象当成发布成功。
 */
export interface ReviewPublishReceipt {
  reviewId: string
  restaurantId: string | null
  windowId: string | null
  replayed: boolean
}

const ID_PATTERN = /^[1-9]\d{0,18}$/
const MAX_ID = 9223372036854775807n

function isId(value: unknown): value is string {
  return typeof value === 'string' && ID_PATTERN.test(value) && BigInt(value) <= MAX_ID
}

/** 去掉首尾空白后按 Unicode 码点计数，与后端“码点”口径一致。 */
export function reviewBodyLength(value: string): number {
  return Array.from(value.trim()).length
}

export function normalizeReviewBody(value: string): string {
  return value.trim()
}

export function isValidScore(value: number): boolean {
  return Number.isFinite(value) && value >= 0 && value <= 10 && Number.isInteger(value * 2)
}

export function isValidScores(scores: ReviewScores): boolean {
  return (['taste', 'value', 'service', 'ambience'] as ScoreKey[]).every((key) =>
    isValidScore(scores[key]),
  )
}

function readFileSize(filePath: string): number | null {
  try {
    const info = wx.getFileSystemManager().getFileInfoSync(filePath)
    return typeof info.size === 'number' ? info.size : null
  } catch {
    return null
  }
}

function readImageSize(filePath: string): Promise<{ width: number; height: number } | null> {
  return new Promise((resolve) => {
    wx.getImageInfo({
      src: filePath,
      success: (result) =>
        resolve(
          result.width > 0 && result.height > 0
            ? { width: result.width, height: result.height }
            : null,
        ),
      fail: () => resolve(null),
    })
  })
}

function compress(
  filePath: string,
  quality: number,
  landscape: boolean,
): Promise<string> {
  return new Promise((resolve) => {
    wx.compressImage({
      src: filePath,
      quality,
      // 只限制最长边，避免竖图按宽度压缩后高度仍超过 1200px。
      ...(landscape ? { compressedWidth: 1200 } : { compressedHeight: 1200 }),
      success: (result) => resolve(result.tempFilePath || filePath),
      // 压缩失败时仍尝试原图，最终由服务端校验格式与大小。
      fail: () => resolve(filePath),
    })
  })
}

/**
 * 逐级压缩到 300KiB 以内。
 *
 * 先读取宽高决定限制长边还是短边；读不到尺寸时按宽度限制。
 * 读不到文件大小时交给服务端做最终校验，不在前端伪报成功；
 * 仍超限时明确要求更换照片，避免把 413 解释成登录或网络问题。
 */
export async function prepareReviewPhoto(filePath: string): Promise<string> {
  const size = await readImageSize(filePath)
  const landscape = !size || size.width >= size.height
  let candidate = filePath
  for (const quality of QUALITY_STEPS) {
    candidate = await compress(filePath, quality, landscape)
    const bytes = readFileSize(candidate)
    if (bytes === null || bytes <= MAX_UPLOAD_BYTES) return candidate
  }
  throw new Error('照片压缩后仍超过 300KB，请更换更小的照片。')
}

/** 上传单张点评临时图；401 只清理本次上传使用的令牌。 */
export function uploadReviewPhoto(filePath: string): Promise<UploadedPhoto> {
  const token = getToken()
  if (!token) {
    return Promise.reject(new ApiError('请先登录。', 'AUTH_UNAUTHENTICATED', 401))
  }
  return new Promise((resolve, reject) => {
    wx.uploadFile({
      url: `${API_BASE_URL}/api/files/images?purpose=REVIEW`,
      filePath,
      name: 'file',
      header: { Authorization: `Bearer ${token}` },
      timeout: 30000,
      success(result) {
        let body: ReturnType<typeof asEnvelope> = null
        try {
          body = asEnvelope(JSON.parse(result.data))
        } catch {
          body = null
        }
        if (result.statusCode >= 200 && result.statusCode < 300) {
          const data = body && body.success ? (body.data as Partial<UploadedPhoto> | null) : null
          if (!data || !isId(data.fileId)) {
            reject(new ApiError('上传响应格式异常，请重试。', 'INVALID_RESPONSE', result.statusCode))
            return
          }
          resolve({
            fileId: data.fileId,
            byteSize: typeof data.byteSize === 'number' ? data.byteSize : null,
            width: typeof data.width === 'number' ? data.width : null,
            height: typeof data.height === 'number' ? data.height : null,
          })
          return
        }
        if (result.statusCode === 401) clearSession(token)
        reject(
          new ApiError(
            messageFrom(body, result.statusCode),
            body?.code ?? 'HTTP_ERROR',
            result.statusCode,
          ),
        )
      },
      fail() {
        reject(new ApiError('图片上传失败，请检查网络后重试。', 'NETWORK_ERROR', 0))
      },
    })
  })
}

/** 取消未绑定的临时图；删除意图已保存的 202 也算成功，失败由到期清理兜底。 */
export async function cancelReviewPhoto(fileId: string): Promise<void> {
  if (!isId(fileId)) return
  await request<undefined>({
    path: `/api/files/images/${encodeURIComponent(fileId)}`,
    method: 'DELETE',
    auth: 'required',
  })
}

/**
 * 发布前按服务端同一规则校验，返回规范化后的正文。
 *
 * 这里只减少明显无效的请求，授权、窗口营业状态、图片归属和内容冲突
 * 仍由服务端最终判定，前端不能因为本地通过就假定发布成功。
 */
function validatePublishInput(input: ReviewPublishInput): string {
  const body = normalizeReviewBody(input.body)
  const length = Array.from(body).length
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(input.clientRequestId)) {
    throw new Error('发布请求编号无法确认，请重新填写。')
  }
  if (!isValidScores(input.scores)) {
    throw new Error('评分需为 0–10 且步进 0.5。')
  }
  if (typeof input.recommended !== 'boolean') {
    throw new Error('推荐态度无法确认。')
  }
  if (length < MIN_REVIEW_BODY || length > MAX_REVIEW_BODY) {
    throw new Error(`请填写 ${MIN_REVIEW_BODY}–${MAX_REVIEW_BODY} 字的点评正文。`)
  }
  if (
    input.photoIds.length > MAX_REVIEW_PHOTOS ||
    new Set(input.photoIds).size !== input.photoIds.length ||
    input.photoIds.some((id) => !isId(id))
  ) {
    throw new Error('点评照片数量或编号不符合要求。')
  }
  return body
}

/** 餐厅与窗口请求体完全一致，只有路径和目标类型不同。 */
function publishPayload(input: ReviewPublishInput, body: string) {
  return {
    clientRequestId: input.clientRequestId,
    scores: {
      taste: input.scores.taste,
      value: input.scores.value,
      service: input.scores.service,
      ambience: input.scores.ambience,
    },
    recommended: input.recommended,
    body,
    photoIds: input.photoIds,
  }
}

/**
 * 发布餐厅点评。
 *
 * 响应必须回指本次餐厅且不带窗口身份，避免把其他店铺或同号窗口的
 * 回执当成发布成功；服务端仍是最终校验与授权来源。
 */
export async function publishReview(
  restaurantId: string,
  input: ReviewPublishInput,
): Promise<ReviewPublishReceipt> {
  const body = validatePublishInput(input)
  if (!isId(restaurantId)) {
    throw new Error('店铺编号无效，无法发布点评。')
  }

  const raw = await request<{
    reviewId: string
    restaurantId: string | null
    windowId: string | null
    replayed: boolean
  }>({
    path: `/api/restaurants/${encodeURIComponent(restaurantId)}/reviews`,
    method: 'POST',
    auth: 'required',
    data: publishPayload(input, body),
  })
  if (
    !raw ||
    !isId(raw.reviewId) ||
    raw.restaurantId !== restaurantId ||
    raw.windowId != null ||
    typeof raw.replayed !== 'boolean'
  ) {
    throw new ApiError('发布结果无法确认，请稍后核对点评列表。', 'INVALID_RESPONSE', 200)
  }
  return {
    reviewId: raw.reviewId,
    restaurantId: raw.restaurantId,
    windowId: null,
    replayed: raw.replayed,
  }
}

/**
 * 发布食堂窗口点评。
 *
 * 窗口与餐厅可能使用相同数值编号，因此必须走 canteen-windows 路径，
 * 并核对回执中的 windowId；餐厅字段必须为空，不能把餐厅点评回执混用。
 */
export async function publishWindowReview(
  windowId: string,
  input: ReviewPublishInput,
): Promise<ReviewPublishReceipt> {
  const body = validatePublishInput(input)
  if (!isId(windowId)) {
    throw new Error('窗口编号无效，无法发布点评。')
  }

  const raw = await request<{
    reviewId: string
    restaurantId: string | null
    windowId: string | null
    replayed: boolean
  }>({
    path: `/api/canteen-windows/${encodeURIComponent(windowId)}/reviews`,
    method: 'POST',
    auth: 'required',
    data: publishPayload(input, body),
  })
  if (
    !raw ||
    !isId(raw.reviewId) ||
    raw.windowId !== windowId ||
    raw.restaurantId != null ||
    typeof raw.replayed !== 'boolean'
  ) {
    throw new ApiError('发布结果无法确认，请稍后核对窗口点评列表。', 'INVALID_RESPONSE', 200)
  }
  return {
    reviewId: raw.reviewId,
    restaurantId: null,
    windowId: raw.windowId,
    replayed: raw.replayed,
  }
}
