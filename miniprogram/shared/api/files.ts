import { API_BASE_URL } from '../../config/env'
import { clearSession, getToken } from '../auth/session'
import { isLongId } from '../lib/id'
import { ApiError, asEnvelope, messageFrom, request } from './request'

/** 图片用途必须与后端绑定规则一致：点评图与投稿图不能混用。 */
export type ImagePurpose = 'REVIEW' | 'RESTAURANT'

export interface UploadedImage {
  fileId: string
  byteSize: number | null
  width: number | null
  height: number | null
}

/**
 * 上传单张临时图并换取 fileId。
 *
 * 临时图尚未绑定业务记录，发布/投稿失败时仍由服务端到期清理；
 * 401 只清理本次上传实际使用的令牌，不能影响其他标签页已更新的登录态。
 */
export function uploadImage(
  filePath: string,
  purpose: ImagePurpose,
): Promise<UploadedImage> {
  const token = getToken()
  if (!token) {
    return Promise.reject(new ApiError('请先登录。', 'AUTH_UNAUTHENTICATED', 401))
  }
  return new Promise((resolve, reject) => {
    wx.uploadFile({
      url: `${API_BASE_URL}/api/files/images?purpose=${purpose}`,
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
          const data = body && body.success ? (body.data as Partial<UploadedImage> | null) : null
          if (!data || !isLongId(data.fileId)) {
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
export async function cancelImage(fileId: string): Promise<void> {
  if (!isLongId(fileId)) return
  await request<undefined>({
    path: `/api/files/images/${encodeURIComponent(fileId)}`,
    method: 'DELETE',
    auth: 'required',
  })
}
