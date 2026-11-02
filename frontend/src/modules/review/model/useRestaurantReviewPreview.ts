import { ref, watch, onScopeDispose, type Ref } from 'vue'
import { fetchReviewPage } from '../api/reviewApi'

/** 首页每店只读取第一页（当前目录最多8店），不遍历历史、不计算评分。
 * 换店或销毁立即取消请求；失败不解释为没有点评。
 */
export function useRestaurantReviewPreview(restaurantId: Ref<string>) {
  const photo = ref<{ url: string; label: string } | null>(null)
  const count = ref<number | null>(null)
  const state = ref<'loading' | 'ready' | 'error'>('loading')
  let generation = 0
  let controller: AbortController | undefined
  const stop = watch(
    restaurantId,
    async (id) => {
      const ticket = ++generation
      controller?.abort()
      controller = new AbortController()
      photo.value = null
      count.value = null
      state.value = 'loading'
      try {
        const page = await fetchReviewPage(id, controller.signal)
        if (ticket !== generation) return
        count.value = page.totalReviews
        // 仅展示首屏作者组已有照片，不声称它是商家封面或全店最新照片。
        const review = page.records.find((item) => item.photos.length)
        if (review)
          photo.value = {
            url: review.photos[0]!.url,
            label: review.body.includes('联调模拟') ? '示意配图 · 非本店实拍' : '同学点评配图',
          }
        state.value = 'ready'
      } catch {
        if (ticket === generation) state.value = 'error'
      }
    },
    { immediate: true },
  )
  onScopeDispose(() => {
    generation++
    controller?.abort()
    stop()
  })
  return { photo, count, state }
}
