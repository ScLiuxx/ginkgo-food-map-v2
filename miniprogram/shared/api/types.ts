/** 后端统一响应封装，与 docs/api.md 的“通用约定”保持一致。 */
export interface ApiEnvelope<T> {
  success: boolean
  code: string
  message: string
  data: T
  timestamp: string
}
