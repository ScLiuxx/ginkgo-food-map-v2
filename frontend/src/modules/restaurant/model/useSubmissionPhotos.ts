import { ref } from 'vue'
import { getAccessToken } from '@/shared/api/authSession'
import { compressPhoto } from '@/modules/file/model/photos'
import { useDraftPhotos } from '@/modules/file/model/useDraftPhotos'
import { uploadImage, cancelImage } from '@/modules/file/api/mediaApi'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'

/** 两类图片各自持有上传草稿，既有私有图复制为本次临时文件，不对原BOUND文件执行删除。 */
export function useSubmissionPhotos(purpose: 'RESTAURANT' | 'REVIEW', owner: () => string | null) {
  const draft = useDraftPhotos(
    {
      uploadPhoto: (file, token) => uploadImage(file, token, purpose),
      cancelPhoto: cancelImage,
    },
    owner,
  )
  const processing = ref(false),
    error = ref('')
  let version = 0
  function clear(bound = false) {
    version++
    processing.value = false
    error.value = ''
    draft.clear(bound)
  }
  /** 压缩前后的账号与版本都必须一致，离页或切换账号后迟到文件不能写入新草稿。 */
  async function addFiles(files: FileList | null) {
    if (!files || processing.value || !owner()) return
    error.value = ''
    if (files.length + draft.photos.value.length > 5) {
      error.value = '最多添加5张图片，请先移除多余图片。'
      return
    }
    const ticket = version,
      token = owner()
    processing.value = true
    try {
      for (const file of Array.from(files)) {
        const compressed = await compressPhoto(file)
        if (ticket !== version || token !== owner() || token !== getAccessToken()) return
        draft.add(compressed)
      }
    } catch (reason) {
      if (ticket === version) error.value = toErrorMessage(reason)
    } finally {
      if (ticket === version) processing.value = false
    }
  }
  /** 读取接口故意不暴露文件ID；编辑/重提时复制JPEG字节，提交前仍保留原申请全部绑定。 */
  async function copyExisting(positions: number[], read: (position: number) => Promise<Blob>) {
    const ticket = version,
      token = owner()
    const blobs = await Promise.all(positions.map(read))
    if (ticket !== version || token !== owner() || token !== getAccessToken()) return
    for (let index = 0; index < blobs.length; index++) {
      draft.add(
        new File([blobs[index]!], `${purpose.toLowerCase()}-${index + 1}.jpg`, {
          type: 'image/jpeg',
        }),
      )
    }
  }
  return { ...draft, processing, error, addFiles, copyExisting, clear }
}
export type SubmissionPhotos = ReturnType<typeof useSubmissionPhotos>
