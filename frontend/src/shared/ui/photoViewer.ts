export interface ViewerPhoto {
  id: string
  url: string
  alt?: string
  caption?: string
  /** 外部相册沿用原有有界加载队列，空URL不会触发无效图片请求。 */
  loading?: boolean
  error?: string
}

export interface PhotoSelection {
  photos: ViewerPhoto[]
  index: number
}
