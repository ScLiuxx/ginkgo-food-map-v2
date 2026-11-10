/** 上传前的图片压缩。点评与投稿共用同一套大小上限，避免不同入口压出不同质量。 */
const MAX_UPLOAD_BYTES = 300 * 1024
const MAX_EDGE = 1200
const QUALITY_STEPS = [80, 62, 45]

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

function compress(filePath: string, quality: number, landscape: boolean): Promise<string> {
  return new Promise((resolve) => {
    wx.compressImage({
      src: filePath,
      quality,
      // 只限制最长边，避免竖图按宽度压缩后高度仍超过 1200px。
      ...(landscape ? { compressedWidth: MAX_EDGE } : { compressedHeight: MAX_EDGE }),
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
export async function prepareImage(filePath: string): Promise<string> {
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
