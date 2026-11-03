import { computed, reactive, ref } from 'vue'
import { getAccessToken } from '@/shared/api/authSession'
/** 文件传输由业务调用方提供，草稿不依赖点评或商户模块。 */
interface PhotoTransport {
  uploadPhoto?: (file: File, token: string) => Promise<string>
  cancelPhoto?: (id: string, token: string) => Promise<void>
}

export interface DraftPhoto {
  file: File
  url: string
  fileId?: string
  state: 'uploading' | 'uploaded' | 'failed'
  error: string
  removed: boolean
  owner: string
}

/** 本地预览与云端上传分别管理；移除后晚到成功的文件立即发起清理，不回填新草稿。 */
export function useDraftPhotos(gateway: PhotoTransport, owner: () => string | null) {
  const photos = ref<DraftPhoto[]>([])
  const pending = computed(() => photos.value.some((p) => p.state === 'uploading'))
  const failed = computed(() => photos.value.some((p) => p.state === 'failed'))

  function cancel(photo: DraftPhoto) {
    if (photo.fileId && gateway.cancelPhoto)
      void gateway.cancelPhoto(photo.fileId, photo.owner).catch(() => {
        // 会话失效或断网时由后端临时文件有效期兜底，不阻止用户关闭表单。
      })
  }
  async function upload(photo: DraftPhoto) {
    photo.state = 'uploading'
    photo.error = ''
    try {
      if (gateway.uploadPhoto) photo.fileId = await gateway.uploadPhoto(photo.file, photo.owner)
      photo.state = 'uploaded'
      if (photo.removed || owner() !== photo.owner || getAccessToken() !== photo.owner)
        cancel(photo)
    } catch (reason) {
      photo.state = 'failed'
      photo.error = reason instanceof Error ? reason.message : '上传失败，请重试。'
    }
  }
  /** 调用方先完成压缩；加入顺序就是最终照片顺序，与上传完成顺序无关。 */
  function add(file: File) {
    const token = owner()
    if (!token || photos.value.length >= 5) return
    const photo = reactive<DraftPhoto>({
      file,
      url: URL.createObjectURL(file),
      state: 'uploading',
      error: '',
      removed: false,
      owner: token,
    })
    photos.value.push(photo)
    void upload(photo)
  }
  function remove(index: number, bound = false) {
    const photo = photos.value.splice(index, 1)[0]
    if (!photo) return
    photo.removed = true
    URL.revokeObjectURL(photo.url)
    if (!bound) cancel(photo)
  }
  /** 成功回执后的照片已绑定，只释放本地资源，不能再调用临时删除接口。 */
  function clear(bound = false) {
    while (photos.value.length) remove(0, bound)
  }
  function retry(photo: DraftPhoto) {
    if (
      !photo.removed &&
      photo.state === 'failed' &&
      owner() === photo.owner &&
      getAccessToken() === photo.owner
    )
      void upload(photo)
  }
  return { photos, pending, failed, add, remove, clear, retry }
}
