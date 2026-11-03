/** 浏览器解码并压缩，拒绝SVG/非图片；只创建本地草稿，不代表已经上传OSS。 */
export async function compressPhoto(file: File): Promise<File> {
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type))
    throw new Error('请选择JPEG、PNG或WebP图片。')
  if (file.size > 20 * 1024 * 1024) throw new Error('原图不能超过20MB。')
  let bitmap: ImageBitmap
  try {
    bitmap = await createImageBitmap(file)
  } catch {
    throw new Error('图片无法读取，请换一张完整的JPEG、PNG或WebP图片。')
  }
  try {
    if (!bitmap.width || !bitmap.height || bitmap.width * bitmap.height > 40_000_000)
      throw new Error('图片尺寸过大。')
    const ratio = Math.min(1, 1200 / Math.max(bitmap.width, bitmap.height))
    const canvas = document.createElement('canvas')
    canvas.width = Math.max(1, Math.round(bitmap.width * ratio))
    canvas.height = Math.max(1, Math.round(bitmap.height * ratio))
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new Error('当前浏览器无法处理图片。')
    ctx.fillStyle = '#fff'
    ctx.fillRect(0, 0, canvas.width, canvas.height)
    ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height)
    let result: Blob | null = null
    for (const quality of [0.86, 0.72, 0.58, 0.44, 0.3]) {
      result = await new Promise<Blob | null>((resolve) =>
        canvas.toBlob(resolve, 'image/jpeg', quality),
      )
      if (result && result.size <= 200 * 1024) break
    }
    if (!result || result.size > 300 * 1024) throw new Error('压缩后仍超过300KB，请换一张图片。')
    return new File([result], 'image.jpg', { type: 'image/jpeg' })
  } finally {
    bitmap.close()
  }
}
