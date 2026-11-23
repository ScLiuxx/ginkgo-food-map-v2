import { reactive } from 'vue'

/** 同一页面内按用户更新图片地址，保存后所有已挂载头像同步重新读取，不触碰登录凭证。 */
export const avatarVersions = reactive<Record<string, number>>({})
/** 只推进保存成功账号的地址版本，其他人的头像和身份状态不受影响。 */
export function avatarUpdated(userId: string) {
  avatarVersions[userId] = (avatarVersions[userId] ?? 0) + 1
}

/** 头像采用居中方形裁剪；预览和最终上传使用同一个JPEG，避免预览与成品不一致。 */
export async function prepareAvatar(file: File): Promise<File> {
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type))
    throw new Error('请选择JPEG、PNG或WebP图片。')
  if (file.size > 20 * 1024 * 1024) throw new Error('原图不能超过20MB。')
  let bitmap: ImageBitmap
  try {
    bitmap = await createImageBitmap(file)
  } catch {
    throw new Error('图片无法读取，请换一张图片。')
  }
  try {
    if (!bitmap.width || !bitmap.height || bitmap.width * bitmap.height > 40_000_000)
      throw new Error('图片尺寸过大。')
    const side = Math.min(bitmap.width, bitmap.height)
    const canvas = document.createElement('canvas')
    canvas.width = canvas.height = Math.min(side, 512)
    const context = canvas.getContext('2d')
    if (!context) throw new Error('当前浏览器无法处理图片。')
    context.fillStyle = '#fff'
    context.fillRect(0, 0, canvas.width, canvas.height)
    context.drawImage(
      bitmap,
      (bitmap.width - side) / 2,
      (bitmap.height - side) / 2,
      side,
      side,
      0,
      0,
      canvas.width,
      canvas.height,
    )
    for (const quality of [0.86, 0.72, 0.58, 0.44, 0.3]) {
      const blob = await new Promise<Blob | null>((resolve) =>
        canvas.toBlob(resolve, 'image/jpeg', quality),
      )
      if (blob && blob.size <= 300 * 1024)
        return new File([blob], 'avatar.jpg', { type: 'image/jpeg' })
    }
    throw new Error('图片压缩失败，请换一张图片。')
  } finally {
    bitmap.close()
  }
}
